package io.dbtower.insight.internal.job;

import io.dbtower.insight.QuerySnapshotRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스냅샷 보존 정책 + 파티션 수명주기 (V18에서 확장) — 보존 기간이 지난 QuerySnapshot을 정리한다.
 *
 * 수집기(SnapshotScheduler)는 60초마다 인스턴스당 최대 100행을 쌓는데(인스턴스 5대면 하루 72만 행),
 * 벌크 DELETE는 (1) 느리고 (2) dead tuple 블로트를 남긴다(공간 미반환 — VACUUM FULL 없이는 안 준다).
 * V18부터 query_snapshot이 월별 RANGE 파티션이라 정리가 두 갈래다:
 * - 통째로 기한이 지난 달 파티션 = DROP TABLE — 즉시 끝나고 블로트가 없다(파일 삭제).
 * - 기한이 걸쳐 있는 파티션 내부 = 기존 DELETE — 파티션 프루닝으로 그 파티션만 스캔하고,
 *   남는 블로트도 그 파티션이 다음 달 DROP될 때 함께 사라진다(블로트 수명이 유한해진다).
 * 파티션 선생성(이번 달·다음 달)도 여기서 한다 — 미생성 월 INSERT는 DEFAULT 파티션이 받는 안전망이
 * 있지만, 정상 경로는 항상 월 파티션이어야 DROP 정리가 성립한다.
 *
 * 파티셔닝 여부는 PG 카탈로그로 확인하고, 아니면(H2 테스트·전환 전 DB) 기존 DELETE로 폴백한다 —
 * 기능이 스토리지 형태에 따라 조용히 다르게 동작하는 게 아니라, 같은 계약(보존 N일)을 두 방식으로 지킨다.
 *
 * 기본 보존 7일은 AWS RDS Performance Insights의 선례를 따른다. 장기 보존은 retention-days 0 이하로 끈다.
 */
@Component
public class SnapshotRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRetentionJob.class);

    /** 월 파티션 이름 규약 — V18 마이그레이션·선생성이 같은 형식을 쓴다(DROP 판정은 이름 파싱으로). */
    private static final Pattern PARTITION_NAME = Pattern.compile("query_snapshot_y(\\d{4})m(\\d{2})");

    private final QuerySnapshotRepository snapshotRepository;
    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;

    public SnapshotRetentionJob(QuerySnapshotRepository snapshotRepository,
                                JdbcTemplate jdbcTemplate,
                                @Value("${dbtower.snapshot.retention-days:7}") int retentionDays) {
        this.snapshotRepository = snapshotRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
    }

    // 벌크 DELETE(@Modifying)는 트랜잭션 안에서만 실행 가능 — 스케줄 스레드에는
    // 열린 트랜잭션이 없으므로 여기서 경계를 연다.
    // HA 분산 락(Phase A5): 여러 노드가 동시에 같은 정리를 돌리지 않게 한 노드만 실행한다.
    // 파티션 DROP/CREATE도 이 락 아래라 노드 간 DDL 경합이 없다.
    @Scheduled(fixedDelayString = "${dbtower.snapshot.retention-sweep-ms:3600000}")
    @SchedulerLock(name = "snapshot-retention-sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    @Transactional
    public void sweep() {
        boolean partitioned = isPartitioned();
        if (partitioned) {
            // 보존과 무관하게 파티션 선생성은 항상 — 다음 달로 넘어갈 때 INSERT가 DEFAULT로 새지 않게
            ensureUpcomingPartitions();
        }
        if (retentionDays <= 0) {
            // 보존 무제한 — 운영자가 명시적으로 끈 상태이므로 조용히 지나간다
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int dropped = partitioned ? dropExpiredPartitions(cutoff) : 0;
        int deleted = snapshotRepository.deleteByCapturedAtBefore(cutoff);
        if (dropped > 0 || deleted > 0) {
            log.info("스냅샷 보존 정리 완료 droppedPartitions={} deletedRows={} cutoff={} retentionDays={}",
                    dropped, deleted, cutoff, retentionDays);
        } else {
            // 정상 상태(삭제할 것 없음)가 매시간 INFO를 채우면 노이즈 — debug로 내린다
            log.debug("스냅샷 보존 정리 — 삭제 대상 없음 cutoff={}", cutoff);
        }
    }

    /** query_snapshot이 파티션드인가 — PG 카탈로그가 없는 환경(H2 테스트·전환 전)은 false 폴백. */
    boolean isPartitioned() {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_partitioned_table pt
                                   JOIN pg_class c ON c.oid = pt.partrelid
                                   WHERE c.relname = 'query_snapshot')
                    """, Boolean.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** 이번 달·다음 달 파티션 선생성(멱등) — 정상 INSERT 경로가 항상 월 파티션에 닿게 한다. */
    private void ensureUpcomingPartitions() {
        YearMonth now = YearMonth.now();
        for (YearMonth month : List.of(now, now.plusMonths(1))) {
            jdbcTemplate.execute(createPartitionSql(month));
        }
    }

    /** 통째로 기한이 지난 월 파티션을 DROP — 즉시·블로트 없음. 걸친 파티션은 DELETE 몫으로 남긴다. */
    private int dropExpiredPartitions(LocalDateTime cutoff) {
        List<String> children = jdbcTemplate.queryForList("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = 'query_snapshot'
                """, String.class);
        int dropped = 0;
        for (String child : children) {
            if (droppable(child, cutoff)) {
                jdbcTemplate.execute("DROP TABLE " + child);
                log.info("스냅샷 파티션 DROP — {} (월 전체가 보존 기한 경과)", child);
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * 파티션 이름(query_snapshot_yYYYYmMM)에서 월을 읽어 그 달 전체가 cutoff 이전인지 판정한다.
     * 이름 규약을 안 따르는 자식(DEFAULT 파티션 등)은 건드리지 않는다 — DROP은 보수적으로.
     */
    static boolean droppable(String childName, LocalDateTime cutoff) {
        Matcher m = PARTITION_NAME.matcher(childName);
        if (!m.matches()) {
            return false;
        }
        YearMonth month = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        LocalDate monthEnd = month.plusMonths(1).atDay(1);   // 파티션 상한(exclusive)
        return !monthEnd.atStartOfDay().isAfter(cutoff);
    }

    /** 월 파티션 생성 DDL(멱등) — 이름 규약은 droppable의 파싱과 한 쌍이다. */
    static String createPartitionSql(YearMonth month) {
        return "CREATE TABLE IF NOT EXISTS query_snapshot_y%04dm%02d PARTITION OF query_snapshot FOR VALUES FROM ('%s') TO ('%s')"
                .formatted(month.getYear(), month.getMonthValue(),
                        month.atDay(1), month.plusMonths(1).atDay(1));
    }
}
