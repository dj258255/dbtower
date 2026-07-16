package io.dbtower.slo;

import io.dbtower.PartitionLifecycle;
import io.dbtower.slo.internal.persistence.HealthSampleRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 헬스 샘플 보존 정리 (Phase D4, V19에서 파티션 수명주기 확장) — 삭제 없이는 무한 적재라
 * 메타 DB가 관리 대상보다 먼저 포화된다.
 *
 * 보존 기간(retention-days)은 에러 버짓 회계 기간(window-days)보다 약간 길게 잡는다 — 버짓 계산이
 * 회계 기간 전체를 훑을 수 있어야 하기 때문. 0 이하면 보존 무제한(정리 안 함).
 *
 * V19부터 health_sample도 월별 RANGE 파티션(V18 query_snapshot과 같은 패턴, PartitionLifecycle
 * 공용) — 기한이 통째로 지난 달은 DROP, 걸친 파티션 내부는 DELETE(프루닝), 비파티션은 DELETE 폴백.
 * 보존 35일 + 월 파티션이라 대부분의 달은 "다음다음 달 초"에 통째로 떨어진다.
 */
@Component
public class HealthSampleRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(HealthSampleRetentionJob.class);

    private static final String TABLE = "health_sample";

    private final HealthSampleRepository sampleRepository;
    private final PartitionLifecycle partitions;
    private final int retentionDays;

    public HealthSampleRetentionJob(HealthSampleRepository sampleRepository,
                                    PartitionLifecycle partitions,
                                    @Value("${dbtower.slo.availability.retention-days:35}") int retentionDays) {
        this.sampleRepository = sampleRepository;
        this.partitions = partitions;
        this.retentionDays = retentionDays;
    }

    // 벌크 DELETE는 트랜잭션 안에서만 — 스케줄 스레드에 열린 트랜잭션이 없으므로 여기서 경계를 연다.
    // HA 분산 락: 삭제는 멱등하지만 동시 실행의 불필요한 부하·로그 중복을 막는다. DDL도 이 락 아래.
    @Scheduled(fixedDelayString = "${dbtower.slo.availability.retention-sweep-ms:3600000}")
    @SchedulerLock(name = "slo-health-retention-sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    @Transactional
    public void sweep() {
        boolean partitioned = partitions.isPartitioned(TABLE);
        if (partitioned) {
            partitions.ensureUpcomingPartitions(TABLE);
        }
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int dropped = partitioned ? partitions.dropExpiredPartitions(TABLE, cutoff) : 0;
        int deleted = sampleRepository.deleteBySampledAtBefore(cutoff);
        if (dropped > 0 || deleted > 0) {
            log.info("헬스 샘플 보존 정리 완료 droppedPartitions={} deletedRows={} cutoff={} retentionDays={}",
                    dropped, deleted, cutoff, retentionDays);
        } else {
            log.debug("헬스 샘플 보존 정리 — 삭제 대상 없음 cutoff={}", cutoff);
        }
    }
}
