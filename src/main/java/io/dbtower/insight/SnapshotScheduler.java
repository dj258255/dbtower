package io.dbtower.insight;

import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.QueryStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 등록된 모든 인스턴스의 쿼리 통계를 주기 수집한다.
 *
 * 성능 개선 아크 후보:
 * - 저장이 JPA saveAll(단건 insert 반복)이라 배치가 커지면 느려진다 → JDBC batch insert로 교체하며 실측
 * - 인스턴스가 많아지면 직렬 수집이 주기를 넘길 수 있다 → 수집 소요시간을 로그로 남겨 병목을 관찰한다
 */
@Component
public class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);
    private static final int TOP_N = 100;

    // A9 백오프: 연속 실패한 인스턴스는 수집을 지수적으로 건너뛴다. 죽었거나 힘든 대상 DB를
    // 매 주기 계속 두드리면(재접속 시도 자체가 부하) 진단이 부하 유발자가 된다. 실패가 이어지면
    // 건너뛸 주기 수를 2배씩 늘리고(상한 MAX_SKIP), 한 번 성공하면 즉시 정상 복귀한다.
    private static final int MAX_SKIP = 16;
    private final Map<Long, int[]> backoff = new ConcurrentHashMap<>(); // id -> [남은 skip, 실패 연속 횟수]

    private final DatabaseInstanceRepository instanceRepository;
    private final SnapshotWriter snapshotWriter;
    private final DbmsOperatorFactory operatorFactory;

    public SnapshotScheduler(DatabaseInstanceRepository instanceRepository,
                             SnapshotWriter snapshotWriter,
                             DbmsOperatorFactory operatorFactory) {
        this.instanceRepository = instanceRepository;
        this.snapshotWriter = snapshotWriter;
        this.operatorFactory = operatorFactory;
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
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            if (shouldSkip(instance.getId())) {
                log.debug("스냅샷 수집 건너뜀(백오프) instance={} 남은틱={}",
                        instance.getName(), backoff.get(instance.getId())[0]);
                continue;
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
    }

    /**
     * A9 백오프 판정: 이 틱에서 이 인스턴스를 건너뛸지. 남은 skip 카운트를 1 소진한다.
     * 카운트가 0이 되는 틱엔 다시 실제 수집을 시도한다(성공하면 회복, 실패하면 더 크게 백오프).
     */
    boolean shouldSkip(Long id) {
        int[] state = backoff.get(id);
        if (state == null || state[0] <= 0) {
            return false;
        }
        state[0]--;
        return true;
    }

    /** 성공하면 백오프 상태를 즉시 제거 — 회복한 인스턴스는 다음 틱부터 정상 주기로 돌아온다. */
    void onSuccess(Long id) {
        backoff.remove(id);
    }

    /**
     * 실패하면 연속 실패 횟수를 늘리고, 건너뛸 틱 수를 2^(연속실패-1)로 지수 증가시킨다(상한 MAX_SKIP).
     * 죽은 대상 DB를 매 틱 두드리는 것 자체가 부하(재접속·인증 시도)라, 실패가 이어질수록 간격을 벌린다.
     */
    int onFailure(Long id) {
        int[] state = backoff.computeIfAbsent(id, k -> new int[]{0, 0});
        state[1]++;
        int skip = Math.min(1 << Math.min(state[1] - 1, 30), MAX_SKIP);
        state[0] = skip;
        return skip;
    }
}
