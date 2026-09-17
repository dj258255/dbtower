package io.dbtower.insight.internal.job;

import io.dbtower.insight.AshSample;
import io.dbtower.insight.AshSampleTick;
import io.dbtower.insight.internal.AshSampleWriter;
import io.dbtower.insight.internal.QueryFingerprint;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 활성 세션 샘플러 (ASH) — 1초마다 "지금 누가 무엇을 하고 누가 누구를 막고 있나"를 남긴다.
 *
 * <p><b>왜 만들었나.</b> {@code SessionInfo}와 {@code activeSessions()}는 5기종 전부 구현돼
 * 있는데 어디에도 영속되지 않는다. 화면이 "지금"만 보여주고 지나가므로, 장애가 끝난 뒤에는
 * 블로킹 관계를 복원할 수 없다. 영속되는 {@code wait_event_snapshot}은 5분 주기 event별
 * 집계라 세션·쿼리·블로커가 GROUP BY에서 이미 사라진 뒤다.
 *
 * <p><b>선례.</b> Oracle ASH(1초, SGA 순환 버퍼 → AWR 10:1), AWS RDS Performance Insights(1초),
 * SolarWinds DPA(every active session, every second), pgsentinel(ring buffer). 이 카테고리의
 * 표준 기능이고, DBTower에 없던 것이 결손이었다.
 *
 * <p><b>A9 원칙과의 충돌.</b> {@link WaitEventSnapshotJob}은 "조회 자체가 부하가 되면 안 된다"는
 * 근거로 5분 주기를 골랐다. 해상도를 300배 올리는 이 잡은 그 원칙과 정면으로 부딪친다. 그래서
 * 원칙을 말로 지키는 대신 재서 지킨다 — {@code collect_ms}를 틱마다 남기고, 락 폭풍을 주입한
 * 상태에서 결측률이 어떻게 변하는지를 계측한다. 그 수치가 이 잡의 존폐를 정한다.
 *
 * <p><b>백프레셔 정책: 쌓지 않고 떨어뜨린다.</b> 대상이 느려 직전 틱이 안 끝났으면 이번 틱을
 * 버리고 {@code SKIPPED_INFLIGHT}로 기록한다. 큐에 쌓으면 관측이 대상을 더 느리게 만들고,
 * 정확히 관측이 가장 필요한 순간(락 폭풍)에 커넥션을 잡아먹는다. Oracle ASH도 AWS PI도 부하가
 * 높으면 샘플을 떨어뜨리는 쪽을 골랐다. 떨어뜨린 사실은 숨기지 않고 틱 메타에 남긴다.
 *
 * <p><b>범위: PostgreSQL 하나.</b> 논거를 증명하는 데 한 기종이면 충분하고, 5기종으로 넓히면
 * 부피만 는다. {@code DbmsOperator} 인터페이스는 손대지 않으므로 다른 기종 경로는 무변경이다.
 *
 * <p><b>기본 비활성.</b> {@code dbtower.ash.enabled=false}가 기본이라 켜지 않으면 기존 동작이
 * 100% 그대로다. 롤백은 이 설정 한 줄이다.
 */
@Component
@ConditionalOnProperty(name = "dbtower.ash.enabled", havingValue = "true")
public class AshSamplerJob {

    private static final Logger log = LoggerFactory.getLogger(AshSamplerJob.class);

    /**
     * 락 보유 하한을 주기의 90%로 잡아, 노드 타이머가 어긋나도 같은 초를 두 노드가 재수집하지
     * 못하게 한다(fixedDelay는 노드마다 독립적으로 흐른다). 상한은 크래시 시 해제 안전망.
     */
    private static final Duration LOCK_AT_LEAST = Duration.ofMillis(900);
    private static final Duration LOCK_AT_MOST = Duration.ofSeconds(10);

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final AshSampleWriter writer;
    private final LockProvider lockProvider;

    private final int maxSessionsPerTick;
    private final int retentionDays;
    private final ExecutorService pool;

    /** 이 JVM 기동 1회 = 1 run. 재기동하면 seq가 1부터 다시 시작하므로 결번 판정의 범위가 된다. */
    private final UUID runId = UUID.randomUUID();
    private final Map<Long, AtomicLong> seqByInstance = new ConcurrentHashMap<>();
    /** 인스턴스별 진행 중 여부 — 직전 틱이 안 끝났으면 이번 틱을 버린다(쌓지 않는다). */
    private final Map<Long, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    public AshSamplerJob(RegistryService registryService,
                         DbmsOperatorFactory operatorFactory,
                         AshSampleWriter writer,
                         LockProvider lockProvider,
                         @Value("${dbtower.ash.max-sessions-per-tick:500}") int maxSessionsPerTick,
                         @Value("${dbtower.ash.workers:4}") int workers,
                         @Value("${dbtower.ash.retention-days:7}") int retentionDays) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.writer = writer;
        this.lockProvider = lockProvider;
        this.maxSessionsPerTick = maxSessionsPerTick;
        this.retentionDays = retentionDays;
        this.pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "ash-sampler");
            t.setDaemon(true);
            return t;
        });
        log.info("ASH 샘플러 기동 runId={} cap={} workers={} 보존={}일",
                runId, maxSessionsPerTick, workers, retentionDays);
    }

    @Scheduled(fixedDelayString = "${dbtower.ash.interval-ms:1000}")
    public void sample() {
        if (pool.isShutdown()) {
            return; // 종료 중 submit은 RejectedExecutionException 노이즈만 남긴다
        }
        java.util.Optional<SimpleLock> lock = lockProvider.lock(
                new LockConfiguration(Instant.now(), "ash-collect", LOCK_AT_MOST, LOCK_AT_LEAST));
        if (lock.isEmpty()) {
            return; // 다른 노드가 이 틱을 담당한다
        }
        List<Future<?>> futures = new ArrayList<>();
        try {
            // 틱 시작 시각은 건너뛴 틱의 기록에만 쓴다. 샘플의 sampled_at은 인스턴스마다 실제로 조회한 시각이다(#98) —
            // 전에는 틱 시작 시각을 모든 인스턴스에 같이 적어, 대상이 느려 조회가 2초 늦으면 기록 시각이 실제 관측보다
            // 2초 앞서 사건 창 밖으로 벗어났다(docs/experiments/ash-backpressure.md). 인스턴스 간 비교는 seq와 초 단위로 한다
            LocalDateTime tickAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
            for (DatabaseInstance instance : registryService.findAll()) {
                if (instance.getType() != DbmsType.POSTGRESQL || !instance.isCollectionEnabled()) {
                    continue;
                }
                if (pool.isShutdown()) {
                    break;
                }
                // seq는 여기(락을 쥔 스케줄 스레드, 단일)에서 정해 넘긴다. 워커에서 증가시키면
                // 제출 순서와 실행 순서가 어긋날 때 (tickAt, seq) 짝이 뒤집힌다. seq는 결번으로
                // 결측을 판정하는 축이라, 뒤집히면 없는 결측이 생기거나 있는 결측이 가려진다.
                long seq = seqByInstance.computeIfAbsent(instance.getId(), k -> new AtomicLong())
                        .incrementAndGet();
                futures.add(pool.submit(() -> sampleOne(instance, tickAt, seq)));
            }
            // 워커가 전부 끝난 뒤에 락을 놓는다(SnapshotScheduler와 같은 이유) — 락 창 안에서
            // 완료를 보장해야 다른 노드의 다음 틱이 같은 초를 재수집하지 못한다.
            awaitAll(futures);
        } finally {
            lock.get().unlock();
        }
    }

    /** 워커 완료 대기. 개별 워커 예외는 sampleOne이 이미 격리·기록하므로 여기선 경고만 남긴다. */
    private void awaitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.warn("ASH 워커 실행 예외 cause={}",
                        e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            }
        }
    }

    private void sampleOne(DatabaseInstance instance, LocalDateTime tickAt, long seq) {
        long id = instance.getId();
        AtomicBoolean busy = inFlight.computeIfAbsent(id, k -> new AtomicBoolean(false));

        if (!busy.compareAndSet(false, true)) {
            // 직전 틱이 아직 안 끝났다. 쌓지 않고 버리되, 버렸다는 사실은 남긴다 —
            // 이 행이 없으면 나중에 "왜 이 초에 샘플이 없지?"에 답할 수 없다.
            writeTick(instance, tickAt, seq, 0, 0, 0, 0,
                    AshSampleTick.SKIPPED_INFLIGHT, null);
            return;
        }
        long startNs = System.nanoTime();
        LocalDateTime observedAt = tickAt;
        try {
            var operator = operatorFactory.create(instance);
            // 워커 차례·지터를 기다린 뒤 대상에 묻는 바로 그 순간을 기록한다
            observedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
            List<SessionInfo> sessions = operator.activeSessions(maxSessionsPerTick * 2);
            double collectMs = (System.nanoTime() - startNs) / 1_000_000.0;
            int observed = sessions.size();

            // 상한 초과 시 elapsed 내림차순으로 자른다 — Datadog이 느리거나 잦은 쿼리 쪽으로
            // 편향 샘플링하는 것과 같은 선택이다. 오래 걸린 세션이 진단 가치가 높다.
            List<SessionInfo> retained = sessions;
            if (observed > maxSessionsPerTick) {
                retained = sessions.stream()
                        .sorted(Comparator.comparingDouble(SessionInfo::elapsedMs).reversed())
                        .limit(maxSessionsPerTick)
                        .toList();
            }
            LocalDateTime ingestedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

            List<AshSample> rows = new ArrayList<>(retained.size());
            for (SessionInfo s : retained) {
                rows.add(new AshSample(id, observedAt, ingestedAt, runId, seq,
                        s.pid(), s.user(), s.state(), s.waitEvent(),
                        categoryOf(s.waitEvent()), s.blockedByPid(),
                        QueryFingerprint.of(s.query()), s.elapsedMs()));
            }
            writer.saveSamples(rows);
            writeTick(instance, observedAt, seq, observed, retained.size(),
                    observed - retained.size(), collectMs, AshSampleTick.OK, null);
        } catch (RuntimeException e) {
            double collectMs = (System.nanoTime() - startNs) / 1_000_000.0;
            // 실패도 남긴다. 남기지 않으면 결측이 "활성 세션 0건"과 구분되지 않는다.
            writeTick(instance, observedAt, seq, 0, 0, 0, collectMs,
                    AshSampleTick.ERROR, truncate(e.getMessage()));
            log.debug("ASH 샘플 실패 instance={} cause={}", instance.getName(), e.getMessage());
        } finally {
            busy.set(false);
        }
    }

    private void writeTick(DatabaseInstance instance, LocalDateTime tickAt, long seq,
                           int observed, int retained, int dropped, double collectMs,
                           String status, String error) {
        try {
            writer.saveTick(new AshSampleTick(instance.getId(), tickAt,
                    LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS), runId, seq,
                    observed, retained, dropped, collectMs, status, error));
        } catch (RuntimeException e) {
            // 틱 메타 저장 실패까지 예외로 올리면 샘플러가 통째로 멈춘다. 여기서 끊는다.
            log.warn("ASH 틱 메타 저장 실패 instance={} cause={}", instance.getName(), e.getMessage());
        }
    }

    /**
     * PG의 wait_event_type을 여기서 다시 얻을 수 없어(SessionInfo에 없다) 이름으로 근사한다.
     * 정확한 분류가 필요하면 SessionInfo에 카테고리를 추가하는 편이 맞고, 그건 5기종 공통
     * 모델을 바꾸는 일이라 이 아크의 범위를 넘는다. 근사임을 표기해 둔다.
     */
    private static String categoryOf(String waitEvent) {
        if (waitEvent == null || waitEvent.isBlank()) {
            return "CPU";
        }
        String w = waitEvent.toLowerCase();
        if (w.contains("lock") || w.contains("lwlock")) {
            return "Lock";
        }
        if (w.contains("io") || w.contains("read") || w.contains("write") || w.contains("datafile")) {
            return "IO";
        }
        return "Other";
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    public int retentionDays() {
        return retentionDays;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
