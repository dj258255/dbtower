package io.dbtower.insight;

import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.QueryStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /** 인스턴스별 시작 지터 상한(ms) — 워커들이 동시에 대상 DB에 접속을 몰지 않게 조금씩 어긋나게 한다. */
    private static final long JITTER_STEP_MS = 40;

    private final DatabaseInstanceRepository instanceRepository;
    private final SnapshotWriter snapshotWriter;
    private final DbmsOperatorFactory operatorFactory;
    private final ExecutorService pool;

    public SnapshotScheduler(DatabaseInstanceRepository instanceRepository,
                             SnapshotWriter snapshotWriter,
                             DbmsOperatorFactory operatorFactory,
                             @Value("${dbtower.snapshot.workers:4}") int workers) {
        this.instanceRepository = instanceRepository;
        this.snapshotWriter = snapshotWriter;
        this.operatorFactory = operatorFactory;
        this.pool = Executors.newFixedThreadPool(Math.max(1, workers), r -> {
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

    // HA 분산 락(Phase A5): 여러 노드가 동시에 같은 대상 DB를 수집하지 않게 한 시점에 한 노드만 실행한다.
    // lockAtLeastFor=PT50S — 60초 주기의 대부분 동안 락을 붙잡아, 노드 간 타이머가 어긋나도(fixedDelay는
    //   노드마다 독립적으로 흐른다) 다른 노드의 틱이 같은 분(minute) 안에서 재수집하지 못하게 막는다.
    //   짧게 두면(예: 0) 시계 드리프트로 두 노드가 한 주기에 중복 수집한다 — 그래서 주기에 가깝게 잡는다.
    // lockAtMostFor=PT2M — 락 보유자가 크래시했을 때의 해제 상한(안전망)이자, 인스턴스가 많아 수집이
    //   길어질 때 다른 노드가 끼어들지 않도록 실제 수집 시간보다 넉넉히 둔 값. 정상 완료 시엔
    //   lockAtLeastFor(50s)까지만 붙잡으므로 이 상한은 크래시/이상 지연 때만 작동한다.
    @Scheduled(fixedDelayString = "${dbtower.snapshot.interval-ms:60000}")
    @SchedulerLock(name = "snapshot-collect", lockAtLeastFor = "PT50S", lockAtMostFor = "PT2M")
    public void collect() {
        List<Future<?>> futures = new ArrayList<>();
        int order = 0;
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            if (!instance.isCollectionEnabled()) {
                log.debug("스냅샷 수집 비활성(격리) instance={}", instance.getName());
                continue;
            }
            if (shouldSkip(instance.getId())) {
                log.debug("스냅샷 수집 건너뜀(백오프) instance={}", instance.getName());
                continue;
            }
            final long jitterMs = (order++) * JITTER_STEP_MS;
            futures.add(pool.submit(() -> collectOne(instance, jitterMs)));
        }
        // 이 틱의 모든 워커가 끝날 때까지 대기 — ShedLock 창 안에서 완료를 보장(다음 노드 끼어들기 방지).
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
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
}
