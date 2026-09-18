package io.dbtower.insight.internal.job;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.dbtower.PartitionLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ASH 샘플 보존 스윕 — {@code query_snapshot}·{@code wait_event_snapshot}과 대칭(기본 7일).
 *
 * <p>여기 7일만 사는 이유는 다른 스냅샷과 같다. 관제 DB가 관리 대상보다 먼저 포화되면 안 된다.
 * 장기 이력은 lakehouse 몫이고, 그 구조는 Oracle의 V$ACTIVE_SESSION_HISTORY(메모리, 약 1시간)와
 * DBA_HIST_ACTIVE_SESS_HISTORY(AWR, 장기) 분리와 같은 계보다.
 *
 * <p>ASH는 초당 수천 행이라 다른 스냅샷보다 훨씬 빨리 는다. 그래서 스윕 주기를 1시간으로 두되
 * 한 번에 지우는 양을 제한한다 — 대량 DELETE가 메타 DB에 락과 WAL 폭증을 주면, 관측 정리가
 * 관측 대상을 흔드는 같은 자기모순이 된다.
 */
@Component
@ConditionalOnProperty(name = "dbtower.ash.enabled", havingValue = "true")
public class AshSampleRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AshSampleRetentionJob.class);

    /** 한 번의 스윕에서 지울 최대 행 수 — 대량 DELETE의 락·WAL 폭증을 막는다. */
    private static final int DELETE_BATCH = 50_000;

    /** 일 파티션을 며칠 앞질러 만들어 둘지 — 잡이 며칠 못 돌아도 INSERT 가 DEFAULT 로 새지 않게. */
    private static final int PARTITIONS_AHEAD = 3;

    private static final List<String> TABLES = List.of("ash_sample", "ash_sample_tick");

    private final JdbcTemplate jdbc;
    private final PartitionLifecycle partitions;
    private final int retentionDays;

    public AshSampleRetentionJob(JdbcTemplate jdbc,
                                 PartitionLifecycle partitions,
                                 @Value("${dbtower.ash.retention-days:7}") int retentionDays) {
        this.jdbc = jdbc;
        this.partitions = partitions;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${dbtower.ash.retention-sweep-ms:3600000}")
    @SchedulerLock(name = "ash-retention", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        for (String table : TABLES) {
            sweepTable(table, cutoff);
        }
    }

    /**
     * 파티션드면 앞질러 만들고 기한 지난 날을 DROP, 아니면 예전처럼 DELETE 로 폴백한다(#149).
     *
     * <p>폴백을 남기는 이유: H2 로 도는 테스트에는 PG 파티션이 없고, V49 적용 전 설치에서도
     * 이 잡이 돌 수 있다. 어느 쪽인지는 PG 카탈로그로 판별한다.
     */
    private void sweepTable(String table, LocalDateTime cutoff) {
        if (partitions.isPartitioned(table)) {
            partitions.ensureUpcomingDailyPartitions(table, PARTITIONS_AHEAD);
            int dropped = partitions.dropExpiredPartitions(table, cutoff);
            // 파티션 경계에 걸친 날은 DROP 대상이 아니다 — 그 구간만 DELETE 가 맡는다
            int rows = deleteBatched(table, cutoff);
            if (dropped > 0 || rows > 0) {
                log.info("ASH 보존 스윕 {} cutoff={} 파티션={}개 남은행={}행", table, cutoff, dropped, rows);
            }
            return;
        }
        int rows = deleteBatched(table, cutoff);
        if (rows > 0) {
            log.info("ASH 보존 스윕 {} cutoff={} {}행 (파티션 아님 — DELETE 폴백)", table, cutoff, rows);
        }
    }

    /** ctid 서브쿼리로 배치를 끊어 지운다 — 한 트랜잭션이 테이블 전체를 잠그지 않게. */
    private int deleteBatched(String table, LocalDateTime cutoff) {
        int total = 0;
        while (true) {
            int deleted = jdbc.update(
                    "DELETE FROM " + table + " WHERE ctid IN ("
                            + "SELECT ctid FROM " + table + " WHERE sampled_at < ? LIMIT " + DELETE_BATCH + ")",
                    cutoff);
            total += deleted;
            if (deleted < DELETE_BATCH) {
                return total;
            }
        }
    }
}
