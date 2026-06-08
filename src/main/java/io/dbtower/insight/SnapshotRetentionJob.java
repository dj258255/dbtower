package io.dbtower.insight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 스냅샷 보존 정책 — 보존 기간이 지난 QuerySnapshot을 주기적으로 정리한다.
 *
 * 수집기(SnapshotScheduler)는 60초마다 인스턴스당 최대 100행을 쌓는데
 * 지금까지 삭제 로직이 없어 무한 적재였다. 인스턴스 5대면 하루 72만 행 —
 * 진단 플랫폼의 메타 DB가 관리 대상보다 먼저 포화되는 구조적 한계.
 *
 * 기본 보존 7일은 AWS RDS Performance Insights의 선례를 따른다 —
 * PI도 기본 보존이 7일이고 그 이상은 명시적 선택이다.
 * 장기 보존이 필요하면 retention-days를 0 이하로 두어 정리를 끈다.
 */
@Component
public class SnapshotRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRetentionJob.class);

    private final QuerySnapshotRepository snapshotRepository;
    private final int retentionDays;

    public SnapshotRetentionJob(QuerySnapshotRepository snapshotRepository,
                                @Value("${dbtower.snapshot.retention-days:7}") int retentionDays) {
        this.snapshotRepository = snapshotRepository;
        this.retentionDays = retentionDays;
    }

    // 벌크 DELETE(@Modifying)는 트랜잭션 안에서만 실행 가능 — 스케줄 스레드에는
    // 열린 트랜잭션이 없으므로 여기서 경계를 연다.
    @Scheduled(fixedDelayString = "${dbtower.snapshot.retention-sweep-ms:3600000}")
    @Transactional
    public void sweep() {
        if (retentionDays <= 0) {
            // 보존 무제한 — 운영자가 명시적으로 끈 상태이므로 조용히 지나간다
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = snapshotRepository.deleteByCapturedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("스냅샷 보존 정리 완료 deleted={} cutoff={} retentionDays={}",
                    deleted, cutoff, retentionDays);
        } else {
            // 정상 상태(삭제할 것 없음)가 매시간 INFO를 채우면 노이즈 — debug로 내린다
            log.debug("스냅샷 보존 정리 — 삭제 대상 없음 cutoff={}", cutoff);
        }
    }
}
