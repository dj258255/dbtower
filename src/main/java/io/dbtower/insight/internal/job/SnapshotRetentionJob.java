package io.dbtower.insight.internal.job;

import io.dbtower.PartitionLifecycle;
import io.dbtower.insight.QuerySnapshotRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 스냅샷 보존 정책 + 파티션 수명주기 (V18에서 확장) — 보존 기간이 지난 QuerySnapshot을 정리한다.
 *
 * 수집기(SnapshotScheduler)는 60초마다 인스턴스당 최대 100행을 쌓는데(인스턴스 5대면 하루 72만 행),
 * 벌크 DELETE는 (1) 느리고 (2) dead tuple 블로트를 남긴다(공간 미반환 — VACUUM FULL 없이는 안 준다).
 * V18부터 query_snapshot이 월별 RANGE 파티션이라 정리가 두 갈래다:
 * - 통째로 기한이 지난 달 파티션 = DROP TABLE — 즉시 끝나고 블로트가 없다(파일 삭제).
 * - 기한이 걸쳐 있는 파티션 내부 = 기존 DELETE — 파티션 프루닝으로 그 파티션만 스캔하고,
 *   남는 블로트도 그 파티션이 다음 달 DROP될 때 함께 사라진다(블로트 수명이 유한해진다).
 * 파티션 선생성·DROP·판별은 PartitionLifecycle(공용 — health_sample과 동일 패턴)로 위임하고,
 * 비파티션 환경(H2 테스트·전환 전)은 기존 DELETE로 폴백 — 같은 계약(보존 N일)을 두 방식으로 지킨다.
 *
 * 기본 보존 7일은 AWS RDS Performance Insights의 선례를 따른다. 장기 보존은 retention-days 0 이하로 끈다.
 */
@Component
public class SnapshotRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRetentionJob.class);

    private static final String TABLE = "query_snapshot";

    private final QuerySnapshotRepository snapshotRepository;
    private final PartitionLifecycle partitions;
    private final int retentionDays;

    public SnapshotRetentionJob(QuerySnapshotRepository snapshotRepository,
                                PartitionLifecycle partitions,
                                @Value("${dbtower.snapshot.retention-days:7}") int retentionDays) {
        this.snapshotRepository = snapshotRepository;
        this.partitions = partitions;
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
        boolean partitioned = partitions.isPartitioned(TABLE);
        if (partitioned) {
            // 보존과 무관하게 파티션 선생성은 항상 — 다음 달로 넘어갈 때 INSERT가 DEFAULT로 새지 않게
            partitions.ensureUpcomingPartitions(TABLE);
        }
        if (retentionDays <= 0) {
            // 보존 무제한 — 운영자가 명시적으로 끈 상태이므로 조용히 지나간다
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int dropped = partitioned ? partitions.dropExpiredPartitions(TABLE, cutoff) : 0;
        int deleted = snapshotRepository.deleteByCapturedAtBefore(cutoff);
        if (dropped > 0 || deleted > 0) {
            log.info("스냅샷 보존 정리 완료 droppedPartitions={} deletedRows={} cutoff={} retentionDays={}",
                    dropped, deleted, cutoff, retentionDays);
        } else {
            // 정상 상태(삭제할 것 없음)가 매시간 INFO를 채우면 노이즈 — debug로 내린다
            log.debug("스냅샷 보존 정리 — 삭제 대상 없음 cutoff={}", cutoff);
        }
    }
}
