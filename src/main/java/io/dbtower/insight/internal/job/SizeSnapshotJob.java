package io.dbtower.insight.internal.job;

import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.TableStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 오브젝트 크기 주기 영속 (Phase 5 forward) — lakehouse 용량 예측(13단계)의 원료 공급.
 *
 * <p>tableStats()는 화면에 "지금 크기"를 보여줄 뿐이었다. 장기 D-day("몇 달 뒤 꽉 차나")는
 * 크기의 시계열이 있어야 하고, 그 시계열의 장기 보관·회귀 계산은 lakehouse 몫이다 — 여기는
 * 6시간 주기로 영속하고 7일만 보존한다(wait_event·query_snapshot과 같은 운명 분리).
 * 단기 디스크 ETA(78절, Prometheus predict 계열)와는 지평이 다른 별도 층이다.
 *
 * <p>volume_*·max_bytes 컬럼은 이 잡이 아직 채우지 않는다(NULL) — 기종별 볼륨 조회
 * (MSSQL dm_os_volume_stats·Oracle maxbytes)는 후속 아크. 없는 값을 지어내지 않는다.
 */
@Component
public class SizeSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(SizeSnapshotJob.class);
    private static final int TOP_N = 200;

    private static final String INSERT_SQL = """
            INSERT INTO size_snapshot
                (instance_id, captured_at, object_type, object_name, row_estimate, data_bytes, index_bytes)
            VALUES (?, ?, 'table', ?, ?, ?, ?)
            """;

    private final DatabaseInstanceRepository instanceRepository;
    private final DbmsOperatorFactory operatorFactory;
    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public SizeSnapshotJob(DatabaseInstanceRepository instanceRepository,
                           DbmsOperatorFactory operatorFactory,
                           JdbcTemplate jdbc,
                           @Value("${dbtower.size-snapshot.retention-days:7}") int retentionDays) {
        this.instanceRepository = instanceRepository;
        this.operatorFactory = operatorFactory;
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${dbtower.size-snapshot.interval-ms:21600000}")
    @SchedulerLock(name = "size-snapshot-collect", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void collect() {
        LocalDateTime capturedAt = LocalDateTime.now();
        int instances = 0, rows = 0;
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            if (!instance.isCollectionEnabled()) {
                continue;
            }
            try {
                List<TableStat> stats = operatorFactory.create(instance).tableStats(TOP_N);
                if (stats.isEmpty()) {
                    continue;
                }
                jdbc.batchUpdate(INSERT_SQL, stats, stats.size(), (ps, t) -> {
                    ps.setLong(1, instance.getId());
                    ps.setTimestamp(2, Timestamp.valueOf(capturedAt));
                    ps.setString(3, t.tableName());
                    ps.setLong(4, t.rowCount());
                    ps.setLong(5, t.dataBytes());
                    ps.setLong(6, t.indexBytes());
                });
                instances++;
                rows += stats.size();
            } catch (Exception e) {
                log.warn("크기 스냅샷 수집 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
        if (rows > 0) {
            log.info("크기 스냅샷 영속 완료 instances={} rows={}", instances, rows);
        }
    }

    @Scheduled(fixedDelayString = "${dbtower.size-snapshot.retention-sweep-ms:3600000}")
    @SchedulerLock(name = "size-snapshot-retention-sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void sweep() {
        if (retentionDays <= 0) {
            return;
        }
        int deleted = jdbc.update(
                "DELETE FROM size_snapshot WHERE captured_at < ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(retentionDays)));
        if (deleted > 0) {
            log.info("크기 스냅샷 보존 정리 deleted={} retentionDays={}", deleted, retentionDays);
        }
    }
}
