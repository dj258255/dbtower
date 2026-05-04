package io.dbtower.insight;

import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.QueryStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

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

    @Scheduled(fixedDelayString = "${dbtower.snapshot.interval-ms:60000}")
    public void collect() {
        for (DatabaseInstance instance : instanceRepository.findAll()) {
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

                // collect(대상 DB 조회)와 save(플랫폼 DB 저장)를 분리 측정 — 개선 아크 1, 2의 근거 데이터
                log.info("스냅샷 수집 완료 instance={} rows={} collectMs={} saveMs={}",
                        instance.getName(), rows.size(), collectMs, saveMs);
            } catch (Exception e) {
                // 한 인스턴스 실패가 나머지 수집을 막으면 안 된다
                log.warn("스냅샷 수집 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
    }
}
