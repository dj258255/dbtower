package io.dbtower.aiops.internal.job;

import io.dbtower.aiops.AiOperationStatus;
import io.dbtower.aiops.internal.AiOperationMetrics;
import io.dbtower.aiops.internal.AiOperationSettings;
import io.dbtower.aiops.internal.domain.AiOperationJob;
import io.dbtower.aiops.internal.persistence.AiOperationJobRepository;
import io.dbtower.audit.AuditTrail;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.EnumSet;

/**
 * 멈춘 AI 운영 작업을 실패로 정리한다. 자동 재시도는 하지 않는다.
 *
 * <p>실행기가 선점한 뒤 리스를 넘기도록 소식이 없으면 워커가 죽은 것이고, 접수된 채 오래 아무도 가져가지 않으면
 * 릴레이나 큐가 멈춘 것이다. 둘 다 조용히 다시 흘려보내면 같은 장애가 반복되는 동안 모델 호출만 쌓인다 —
 * 실패로 드러내고 사람이 원인을 본 뒤 재시도(POST /retry)하게 했다.</p>
 */
@Component
public class AiOperationReaper {

    private static final Logger log = LoggerFactory.getLogger(AiOperationReaper.class);

    private static final EnumSet<AiOperationStatus> LEASED = EnumSet.of(AiOperationStatus.AUTHORIZED,
            AiOperationStatus.COLLECTING, AiOperationStatus.RETRIEVING, AiOperationStatus.ANALYZING,
            AiOperationStatus.VERIFYING);

    private final AiOperationJobRepository jobs;
    private final AiOperationSettings settings;
    private final AuditTrail auditTrail;
    private final AiOperationMetrics metrics;
    private final TransactionTemplate tx;

    public AiOperationReaper(AiOperationJobRepository jobs, AiOperationSettings settings, AuditTrail auditTrail,
                             AiOperationMetrics metrics, PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.settings = settings;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${dbtower.aiops.reaper-ms:60000}", initialDelayString = "${dbtower.aiops.reaper-initial-delay-ms:60000}")
    @SchedulerLock(name = "aiops-reaper", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    public void scheduledSweep() {
        sweep();
    }

    /** 락 없는 정리 본체 — 스케줄 진입점만 분산 락을 쥔다. 정리 자체는 한 건씩 낙관적 락이라 겹쳐 돌아도 안전하다 */
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        int expired = 0;
        for (AiOperationJob job : jobs.findByStatusInAndLeaseUntilBefore(LEASED, now)) {
            expired += failOne(job.getJobId(), "실행기 응답 없음(리스 만료, 상태 " + job.getStatus() + ")") ? 1 : 0;
        }
        int unclaimed = 0;
        for (AiOperationJob job : jobs.findByStatusAndUpdatedAtBefore(AiOperationStatus.RECEIVED,
                now.minus(settings.receivedTimeout()))) {
            unclaimed += failOne(job.getJobId(), "실행기가 작업을 가져가지 않음(릴레이·큐 확인 필요)") ? 1 : 0;
        }
        if (expired + unclaimed > 0) {
            log.warn("AI 운영 작업 정리: 리스 만료 {}건, 미선점 {}건", expired, unclaimed);
        }
    }

    /** 한 건씩 트랜잭션을 끊는다 — 정리하는 순간 실행기가 전이하면 그 한 건만 충돌로 건너뛰고 나머지는 계속 정리한다. */
    boolean failOne(String jobId, String reason) {
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                AiOperationJob job = jobs.findById(jobId).orElse(null);
                if (job == null || job.getStatus().terminal()) {
                    return false;
                }
                job.fail(reason, OffsetDateTime.now());
                jobs.saveAndFlush(job);
                metrics.recordFinished(job.getType(), job.getStatus(), job.getRequestedAt());
                auditTrail.record("AI 운영 작업 정리 jobId=" + jobId + " reason=" + reason, job.getInstanceId(), 1);
                return true;
            }));
        } catch (ObjectOptimisticLockingFailureException e) {
            return false;
        }
    }
}
