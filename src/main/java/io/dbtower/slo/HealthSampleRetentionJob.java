package io.dbtower.slo;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 헬스 샘플 보존 정리 (Phase D4) — 삭제 없이는 무한 적재라 메타 DB가 관리 대상보다 먼저 포화된다.
 *
 * 보존 기간(retention-days)은 에러 버짓 회계 기간(window-days)보다 약간 길게 잡는다 — 버짓 계산이
 * 회계 기간 전체를 훑을 수 있어야 하기 때문. 0 이하면 보존 무제한(정리 안 함).
 */
@Component
public class HealthSampleRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(HealthSampleRetentionJob.class);

    private final HealthSampleRepository sampleRepository;
    private final int retentionDays;

    public HealthSampleRetentionJob(HealthSampleRepository sampleRepository,
                                    @Value("${dbtower.slo.availability.retention-days:35}") int retentionDays) {
        this.sampleRepository = sampleRepository;
        this.retentionDays = retentionDays;
    }

    // 벌크 DELETE는 트랜잭션 안에서만 — 스케줄 스레드에 열린 트랜잭션이 없으므로 여기서 경계를 연다.
    // HA 분산 락: 삭제는 멱등하지만 동시 실행의 불필요한 부하·로그 중복을 막는다.
    @Scheduled(fixedDelayString = "${dbtower.slo.availability.retention-sweep-ms:3600000}")
    @SchedulerLock(name = "slo-health-retention-sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    @Transactional
    public void sweep() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = sampleRepository.deleteBySampledAtBefore(cutoff);
        if (deleted > 0) {
            log.info("헬스 샘플 보존 정리 완료 deleted={} cutoff={} retentionDays={}", deleted, cutoff, retentionDays);
        } else {
            log.debug("헬스 샘플 보존 정리 — 삭제 대상 없음 cutoff={}", cutoff);
        }
    }
}
