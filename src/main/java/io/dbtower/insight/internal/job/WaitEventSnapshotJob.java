package io.dbtower.insight.internal.job;

import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.WaitEvent;
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
 * 대기 이벤트 주기 영속 (Phase 5 forward, D1) — "지금"만 보여주던 waitEvents()를 이력으로.
 *
 * <p>query_snapshot 수집(SnapshotScheduler)과 같은 운명을 갖되 더 가볍다: 주기가 길고
 * (기본 5분 — 대기 이벤트는 추세 신호라 60초 해상도가 불필요), 인스턴스당 한 번의 시스템
 * 뷰 조회뿐이라 직렬 루프로 충분하다(A9 — 조회 자체가 부하가 되면 안 된다는 원칙은
 * 각 operator의 쿼리 타임아웃이 이미 지킨다). 수집 불가/미지원 기종은 빈 목록이 정상 —
 * 조용히 건너뛴다(B1의 정직 표기를 데이터에도 적용: 없는 걸 지어내지 않는다).
 *
 * <p>보존은 query_snapshot과 대칭(기본 7일) — 장기 이력은 lakehouse 몫이고, 여기는
 * 관제 DB가 무한 성장하지 않는 것이 우선이다(SnapshotRetentionJob과 같은 근거).
 * HA: 수집·보존 모두 ShedLock으로 노드 배타.
 */
@Component
public class WaitEventSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(WaitEventSnapshotJob.class);
    private static final int TOP_N = 50;

    private static final String INSERT_SQL = """
            INSERT INTO wait_event_snapshot
                (instance_id, captured_at, event_name, category, wait_count, total_ms)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final DatabaseInstanceRepository instanceRepository;
    private final DbmsOperatorFactory operatorFactory;
    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public WaitEventSnapshotJob(DatabaseInstanceRepository instanceRepository,
                                DbmsOperatorFactory operatorFactory,
                                JdbcTemplate jdbc,
                                @Value("${dbtower.wait-event.retention-days:7}") int retentionDays) {
        this.instanceRepository = instanceRepository;
        this.operatorFactory = operatorFactory;
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${dbtower.wait-event.interval-ms:300000}")
    @SchedulerLock(name = "wait-event-collect", lockAtLeastFor = "PT1M", lockAtMostFor = "PT4M")
    public void collect() {
        LocalDateTime capturedAt = LocalDateTime.now();
        int instances = 0, rows = 0;
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            if (!instance.isCollectionEnabled()) {
                continue; // 수집 격리 토글 존중(SnapshotScheduler와 동일)
            }
            try {
                List<WaitEvent> events = operatorFactory.create(instance).waitEvents(TOP_N);
                if (events.isEmpty()) {
                    continue; // 미지원 기종/무대기 — 없는 것을 지어내지 않는다
                }
                jdbc.batchUpdate(INSERT_SQL, events, events.size(), (ps, e) -> {
                    ps.setLong(1, instance.getId());
                    ps.setTimestamp(2, Timestamp.valueOf(capturedAt));
                    ps.setString(3, e.event());
                    ps.setString(4, e.category());
                    ps.setLong(5, e.count());
                    ps.setDouble(6, e.totalMs());
                });
                instances++;
                rows += events.size();
            } catch (Exception e) {
                // 한 인스턴스 실패가 나머지를 막으면 안 된다(SnapshotScheduler와 동일한 격리)
                log.warn("대기 이벤트 수집 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
        if (rows > 0) {
            log.info("대기 이벤트 영속 완료 instances={} rows={}", instances, rows);
        }
    }

    @Scheduled(fixedDelayString = "${dbtower.wait-event.retention-sweep-ms:3600000}")
    @SchedulerLock(name = "wait-event-retention-sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void sweep() {
        if (retentionDays <= 0) {
            return; // 보존 무제한 — 운영자가 명시적으로 끈 상태
        }
        int deleted = jdbc.update(
                "DELETE FROM wait_event_snapshot WHERE captured_at < ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(retentionDays)));
        if (deleted > 0) {
            log.info("대기 이벤트 보존 정리 deleted={} retentionDays={}", deleted, retentionDays);
        }
    }
}
