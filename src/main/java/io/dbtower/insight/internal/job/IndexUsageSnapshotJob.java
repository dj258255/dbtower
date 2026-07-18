package io.dbtower.insight.internal.job;

import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.IndexUsage;
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
 * 인덱스 사용 통계 주기 영속 (운영 병목 아크 B3, I2) — indexUsage()의 "지금 누적값"을 이력으로.
 *
 * <p>WaitEventSnapshotJob·SizeSnapshotJob과 같은 뼈대: 긴 주기(기본 6시간 — 인덱스 사용은 느린
 * 추세라 촘촘할 필요가 없다), 인스턴스별 시스템 뷰 조회, 수집 격리 토글 존중, 인스턴스 실패 격리,
 * ShedLock 배타, 7일 보존(장기는 lakehouse). UNSUPPORTED 기종(Oracle)은 NATIVE가 아니라 조용히
 * 건너뛴다 — 없는 통계를 지어내 저장하지 않는다.
 */
@Component
public class IndexUsageSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(IndexUsageSnapshotJob.class);
    private static final int SCAN_LIMIT = 300;

    private static final String INSERT_SQL = """
            INSERT INTO index_usage_snapshot
                (instance_id, captured_at, table_name, index_name, scan_count, size_bytes, is_unique)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final DatabaseInstanceRepository instanceRepository;
    private final DbmsOperatorFactory operatorFactory;
    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public IndexUsageSnapshotJob(DatabaseInstanceRepository instanceRepository,
                                 DbmsOperatorFactory operatorFactory, JdbcTemplate jdbc,
                                 @Value("${dbtower.index-usage.retention-days:7}") int retentionDays) {
        this.instanceRepository = instanceRepository;
        this.operatorFactory = operatorFactory;
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${dbtower.index-usage.interval-ms:21600000}")
    @SchedulerLock(name = "index-usage-collect", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void collect() {
        LocalDateTime capturedAt = LocalDateTime.now();
        int instances = 0, rows = 0;
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            if (!instance.isCollectionEnabled()) {
                continue;
            }
            try {
                List<IndexUsage> usage = operatorFactory.create(instance).indexUsage(SCAN_LIMIT).stream()
                        .filter(u -> IndexUsage.NATIVE.equals(u.source())) // UNSUPPORTED 안내 행은 저장 안 함
                        .toList();
                if (usage.isEmpty()) {
                    continue;
                }
                jdbc.batchUpdate(INSERT_SQL, usage, usage.size(), (ps, u) -> {
                    ps.setLong(1, instance.getId());
                    ps.setTimestamp(2, Timestamp.valueOf(capturedAt));
                    ps.setString(3, u.tableName());
                    ps.setString(4, u.indexName());
                    if (u.scanCount() == null) {
                        ps.setNull(5, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(5, u.scanCount());
                    }
                    if (u.sizeBytes() == null) {
                        ps.setNull(6, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(6, u.sizeBytes());
                    }
                    ps.setBoolean(7, u.unique());
                });
                instances++;
                rows += usage.size();
            } catch (Exception e) {
                log.warn("인덱스 사용 통계 수집 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
        if (rows > 0) {
            log.info("인덱스 사용 통계 영속 완료 instances={} rows={}", instances, rows);
        }
    }

    @Scheduled(fixedDelayString = "${dbtower.index-usage.retention-sweep-ms:3600000}")
    @SchedulerLock(name = "index-usage-retention-sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void sweep() {
        if (retentionDays <= 0) {
            return;
        }
        int deleted = jdbc.update("DELETE FROM index_usage_snapshot WHERE captured_at < ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(retentionDays)));
        if (deleted > 0) {
            log.info("인덱스 사용 통계 보존 정리 deleted={} retentionDays={}", deleted, retentionDays);
        }
    }
}
