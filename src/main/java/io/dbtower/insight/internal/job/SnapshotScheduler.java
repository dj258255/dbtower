package io.dbtower.insight.internal.job;

import io.dbtower.insight.QuerySnapshot;
import io.dbtower.insight.internal.SnapshotWriter;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.InstanceDeletedEvent;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 등록된 모든 인스턴스의 쿼리 통계를 주기 수집한다.
 *
 * <p>스케일 제어 (Phase F): 인스턴스가 많아지면 <b>직렬 수집이 주기를 넘길 수 있다</b>(느린 대상 하나가
 * 뒤 전부를 밀어낸다). 그래서 한 틱 안에서 인스턴스별 수집을 고정 크기 워커 풀로 <b>병렬</b> 실행한다.
 * ShedLock의 노드 배타 의미는 그대로다 — 병렬은 <b>이 노드 안에서만</b>이고, 노드 간에는 여전히 한 노드만
 * 이 메서드를 돈다. collect()는 이 틱의 모든 워커가 끝날 때까지 기다리므로 ShedLock 창 안에서 완료된다.
 */
@Component
public class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);
    private static final int TOP_N = 100;

    // A9 백오프: 연속 실패한 인스턴스는 수집을 지수적으로 건너뛴다. 죽었거나 힘든 대상 DB를
    // 매 주기 계속 두드리면(재접속 시도 자체가 부하) 진단이 부하 유발자가 된다. 실패가 이어지면
    // 건너뛸 주기 수를 2배씩 늘리고(상한 MAX_SKIP), 한 번 성공하면 즉시 정상 복귀한다.
    private static final int MAX_SKIP = 16;
    // 백오프 상태의 int[] 갱신은 read-modify-write라, 병렬 워커에서 안전하도록 접근을 동기화한다.
    // (한 틱에선 인스턴스가 서로 달라 같은 id를 두 워커가 만지지 않지만, 방어적으로 배타화한다.)
    private final Map<Long, int[]> backoff = new ConcurrentHashMap<>(); // id -> [남은 skip, 실패 연속 횟수]

    /** 인스턴스별 시작 지터 단위(ms) — 워커들이 동시에 대상 DB에 접속을 몰지 않게 조금씩 어긋나게 한다. */
    private static final long JITTER_STEP_MS = 40;
    // B-3: 시작 지터 상한(ms). 인스턴스가 많으면 (order++)*STEP가 무한정 커져 후순위 워커가 수 초~수십 초
    // sleep하며 워커 슬롯을 점유(collect 벽시계 팽창)한다. 지터의 목적은 "동시 접속을 조금 흩는" 것뿐이라
    // 상한을 둬도 그 효과는 유지된다. order를 workers로 모듈러해 한 워커 슬롯 주기(step) 안으로 접고 캡을 씌운다.
    private static final long JITTER_CAP_MS = 2000;

    private final DatabaseInstanceRepository instanceRepository;
    private final SnapshotWriter snapshotWriter;
    private final DbmsOperatorFactory operatorFactory;
    private final LockProvider lockProvider;
    private final ExecutorService pool;
    private final int workers;
    private final int shards;

    public SnapshotScheduler(DatabaseInstanceRepository instanceRepository,
                             SnapshotWriter snapshotWriter,
                             DbmsOperatorFactory operatorFactory,
                             LockProvider lockProvider,
                             @Value("${dbtower.snapshot.workers:4}") int workers,
                             @Value("${dbtower.snapshot.shards:1}") int shards) {
        this.instanceRepository = instanceRepository;
        this.snapshotWriter = snapshotWriter;
        this.operatorFactory = operatorFactory;
        this.lockProvider = lockProvider;
        this.workers = Math.max(1, workers);
        this.shards = Math.max(1, shards);
        this.pool = Executors.newFixedThreadPool(this.workers, r -> {
            Thread t = new Thread(r, "dbtower-collect");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // HA 분산 락(Phase A5) + 샤딩(Phase 4): 여러 노드가 같은 대상을 중복 수집하지 않게 하되, 노드가
    // 여럿이면 일을 나눠 든다. 샤드 s∈[0,N)마다 별도 락(snapshot-collect-s)을 각각 시도해, 획득한
    // 샤드의 인스턴스(instanceId % N == s)만 수집한다 — 노드가 여럿이면 락 경쟁으로 샤드가 자연
    // 분산되고, 한 노드만 살아 있으면 그 노드가 전 샤드를 잡는다(페일오버가 설정 없이 유지된다).
    // consistent hashing이 필요 없는 이유: 스냅샷은 카운터 누적의 차분이라 담당 노드가 바뀌어도
    // 데이터가 깨지지 않는다(구간 한 번 끊길 뿐 — ComparisonService가 이미 리셋 내성).
    //
    // shards=1(기본)이면 락 이름을 기존 "snapshot-collect" 그대로 써서 현행과 동작·배타가 동일하다
    // (롤링 배포 중 구버전 노드와도 같은 락을 다툰다). 샤드 수 변경은 전 노드 동시 적용이 전제 —
    // 노드마다 N이 다르면 담당 계산(id % N)이 어긋나 같은 인스턴스를 두 노드가 수집할 수 있다.
    //
    // lockAtLeastFor=PT50S — 60초 주기의 대부분 동안 락을 붙잡아, 노드 간 타이머가 어긋나도(fixedDelay는
    //   노드마다 독립적으로 흐른다) 다른 노드의 틱이 같은 분(minute) 안에서 재수집하지 못하게 막는다.
    // lockAtMostFor=PT2M — 락 보유자가 크래시했을 때의 해제 상한(안전망). 정상 완료 시엔
    //   lockAtLeastFor(50s)까지만 붙잡으므로 이 상한은 크래시/이상 지연 때만 작동한다.
    private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(50);
    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(2);

    @Scheduled(fixedDelayString = "${dbtower.snapshot.interval-ms:60000}")
    public void collect() {
        // B-6: 종료 중이면 시작하지 않는다 — @PreDestroy로 풀이 내려가는 중에 submit하면
        // RejectedExecutionException 노이즈만 남는다(taskScheduler와 이 풀의 파괴 순서는 보장되지 않는다).
        if (pool.isShutdown()) {
            return;
        }
        for (int shard = 0; shard < shards; shard++) {
            String lockName = shards == 1 ? "snapshot-collect" : "snapshot-collect-" + shard;
            Optional<SimpleLock> lock = lockProvider.lock(
                    new LockConfiguration(Instant.now(), lockName, LOCK_AT_MOST, LOCK_AT_LEAST));
            if (lock.isEmpty()) {
                log.debug("스냅샷 샤드 스킵(다른 노드 담당) shard={}", shard);
                continue;
            }
            try {
                collectShard(shard);
            } finally {
                // unlock은 lockAtLeastFor를 존중한다(즉시 해제가 아니라 최소 보유 시각까지 유지) —
                // 같은 분 안에서 다른 노드가 이 샤드를 재수집하지 못하는 보장은 그대로다.
                lock.get().unlock();
            }
        }
    }

    /** 샤드 하나의 담당 인스턴스(instanceId % shards == shard)를 워커 풀로 병렬 수집한다. */
    private void collectShard(int shard) {
        List<Future<?>> futures = new ArrayList<>();
        int order = 0, assigned = 0;
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            if (shards > 1 && instance.getId() % shards != shard) {
                continue; // 다른 샤드 담당 — 그 샤드의 락 보유자가 수집한다
            }
            assigned++;
            if (!instance.isCollectionEnabled()) {
                log.debug("스냅샷 수집 비활성(격리) instance={}", instance.getName());
                continue;
            }
            if (shouldSkip(instance.getId())) {
                log.debug("스냅샷 수집 건너뜀(백오프) instance={}", instance.getName());
                continue;
            }
            if (pool.isShutdown()) {
                break; // 루프 도중 종료가 시작되면 남은 submit을 멈춘다(B-6)
            }
            // B-3: order를 워커 수로 접어 한 슬롯 주기 안에서 흩고, 상한(JITTER_CAP_MS)으로 유한화한다.
            final long jitterMs = Math.min((order++ % workers) * JITTER_STEP_MS, JITTER_CAP_MS);
            futures.add(pool.submit(() -> collectOne(instance, jitterMs)));
        }
        if (shards > 1) {
            // 2노드 분산 실측의 근거 로그 — 어느 노드가 어느 샤드를 몇 개나 담당했는지 남긴다
            log.info("스냅샷 샤드 수집 shard={}/{} instances={}", shard, shards, assigned);
        }
        // 이 샤드의 모든 워커가 끝날 때까지 대기 — 락 창 안에서 완료를 보장(다음 노드 끼어들기 방지).
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                // 이 스케줄 스레드 자신이 인터럽트된 경우에만 플래그를 복원하고 대기를 중단한다(B-4).
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                // 워커 내부 예외는 collectOne이 이미 격리·로깅하므로 여기 오는 건 드물다.
                // 오더라도 인터럽트로 뭉개지 말고 경고만 남기고 나머지 워커를 계속 기다린다(B-4).
                log.warn("스냅샷 워커 실행 예외 cause={}", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            }
        }
    }

    /** 인스턴스 하나를 수집·저장한다(워커 스레드에서 실행). 실패는 자체 격리하고 백오프에 반영한다. */
    private void collectOne(DatabaseInstance instance, long jitterMs) {
        if (jitterMs > 0) {
            try {
                Thread.sleep(jitterMs); // 워커들이 동시에 접속을 몰지 않도록 조금씩 어긋나게 시작
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        long start = System.currentTimeMillis();
        try {
            List<QueryStat> stats = operatorFactory.create(instance).queryStats(TOP_N);
            long collectMs = System.currentTimeMillis() - start;

            LocalDateTime capturedAt = LocalDateTime.now();
            List<QuerySnapshot> rows = stats.stream()
                    .map(s -> new QuerySnapshot(instance.getId(), capturedAt, s.queryId(),
                            s.queryText(), s.calls(), s.totalTimeMs(), s.rowsExamined()))
                    .toList();

            long saveStart = System.currentTimeMillis();
            snapshotWriter.saveBatch(rows);
            long saveMs = System.currentTimeMillis() - saveStart;

            onSuccess(instance.getId());
            // collect(대상 DB 조회)와 save(플랫폼 DB 저장)를 분리 측정 — 개선 아크 1, 2의 근거 데이터
            log.info("스냅샷 수집 완료 instance={} rows={} collectMs={} saveMs={}",
                    instance.getName(), rows.size(), collectMs, saveMs);
        } catch (Exception e) {
            // 한 인스턴스 실패가 나머지 수집을 막으면 안 된다
            int skip = onFailure(instance.getId());
            log.warn("스냅샷 수집 실패 instance={} cause={} 다음_건너뛸틱={}",
                    instance.getName(), e.getMessage(), skip);
        }
    }

    /**
     * A9 백오프 판정: 이 틱에서 이 인스턴스를 건너뛸지. 남은 skip 카운트를 1 소진한다.
     * 카운트가 0이 되는 틱엔 다시 실제 수집을 시도한다(성공하면 회복, 실패하면 더 크게 백오프).
     * synchronized — 병렬 워커에서 int[] read-modify-write를 배타화한다.
     */
    synchronized boolean shouldSkip(Long id) {
        int[] state = backoff.get(id);
        if (state == null || state[0] <= 0) {
            return false;
        }
        state[0]--;
        return true;
    }

    /** 성공하면 백오프 상태를 즉시 제거 — 회복한 인스턴스는 다음 틱부터 정상 주기로 돌아온다. */
    synchronized void onSuccess(Long id) {
        backoff.remove(id);
    }

    /**
     * 실패하면 연속 실패 횟수를 늘리고, 건너뛸 틱 수를 2^(연속실패-1)로 지수 증가시킨다(상한 MAX_SKIP).
     * 죽은 대상 DB를 매 틱 두드리는 것 자체가 부하(재접속·인증 시도)라, 실패가 이어질수록 간격을 벌린다.
     */
    synchronized int onFailure(Long id) {
        int[] state = backoff.computeIfAbsent(id, k -> new int[]{0, 0});
        state[1]++;
        int skip = Math.min(1 << Math.min(state[1] - 1, 30), MAX_SKIP);
        state[0] = skip;
        return skip;
    }

    /** 인스턴스 삭제 이벤트(B-1) — 그 인스턴스의 백오프 상태를 비운다(삭제된 id가 맵에 잔존하지 않게). */
    @EventListener
    public void onInstanceDeleted(InstanceDeletedEvent event) {
        evict(event.instanceId());
    }

    synchronized void evict(long instanceId) {
        backoff.remove(instanceId);
    }
}
