package io.dbtower.review;

import io.dbtower.registry.RegistryService;
import io.dbtower.review.internal.domain.ReviewRequest;
import io.dbtower.review.internal.domain.ReviewRequest.Status;
import io.dbtower.review.internal.persistence.ReviewRequestRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 변경 티켓 게이트의 공개 창구 — 실행 계층(workbench)이 "승인된 티켓인가"를 묻고, 실행권을 얻고, 결과를 남기는 곳.
 *
 * <p>실행 계층은 티켓 상태를 직접 쓰지 않는다. 전이는 전부 여기의 조건부 UPDATE로만 일어난다:
 * APPROVED -> EXECUTING -> EXECUTED, EXECUTED -> ROLLING_BACK -> ROLLED_BACK. 실패가 확정되면(대상 DB 롤백) 실행권을
 * 돌려주고, 커밋 여부를 알 수 없으면 돌려주지 않는다 — 사람이 확인하기 전까지 같은 변경이 두 번 나가지 않게.
 */
@Service
public class ChangeTicketGate {

    /**
     * @param sql       승인된 원문(실행 계층은 이것만 실행한다 — 편집본을 받지 않는다)
     * @param verifySql 변경 전후 실행계획·응답시간을 잴 검증 조회(없으면 null)
     * @param status    PENDING / APPROVED / REJECTED / EXECUTING / EXECUTED / ROLLING_BACK / ROLLED_BACK
     */
    public record ChangeTicket(Long id, Long instanceId, String sql, String verifySql, String status,
                               String requester, String decidedBy) {
    }

    private final ReviewRequestRepository repository;
    private final RegistryService registry;
    private final ApplicationEventPublisher events;

    public ChangeTicketGate(ReviewRequestRepository repository, RegistryService registry, ApplicationEventPublisher events) {
        this.repository = repository;
        this.registry = registry;
        this.events = events;
    }

    /** 티켓 조회 — 팀 범위 밖 인스턴스의 티켓이면 RegistryService가 404로 끊는다(원문 SQL 노출 방지). */
    public ChangeTicket ticket(Long reviewId) {
        ReviewRequest r = repository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("리뷰 요청을 찾을 수 없습니다: " + reviewId));
        registry.findById(r.getInstanceId());
        return new ChangeTicket(r.getId(), r.getInstanceId(), r.getTargetSql(), r.getVerifySql(), r.getStatus().name(),
                r.getRequester(), r.getDecidedBy());
    }

    /** APPROVED -> EXECUTING. false면 이미 누가 실행 중이거나 상태가 바뀌었다. */
    @Transactional
    public boolean claimExecution(Long reviewId) {
        return repository.transition(reviewId, Status.APPROVED, Status.EXECUTING) == 1;
    }

    /** 대상 DB가 롤백을 확정한 실패 — 다시 실행할 수 있게 실행권을 돌려준다. */
    @Transactional
    public void releaseExecution(Long reviewId) {
        repository.transition(reviewId, Status.EXECUTING, Status.APPROVED);
    }

    @Transactional
    public void completeExecution(Long reviewId, Long instanceId, String actor, long affectedRows) {
        if (repository.markExecuted(reviewId, actor, LocalDateTime.now()) == 1) {
            events.publishEvent(new ReviewExecutedEvent(reviewId, instanceId, false, actor, affectedRows));
        }
    }

    /** EXECUTED -> ROLLING_BACK */
    @Transactional
    public boolean claimRollback(Long reviewId) {
        return repository.transition(reviewId, Status.EXECUTED, Status.ROLLING_BACK) == 1;
    }

    @Transactional
    public void releaseRollback(Long reviewId) {
        repository.transition(reviewId, Status.ROLLING_BACK, Status.EXECUTED);
    }

    @Transactional
    public void completeRollback(Long reviewId, Long instanceId, String actor, long restoredRows) {
        if (repository.markRolledBack(reviewId, actor, LocalDateTime.now()) == 1) {
            events.publishEvent(new ReviewExecutedEvent(reviewId, instanceId, true, actor, restoredRows));
        }
    }
}
