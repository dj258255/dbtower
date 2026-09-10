package io.dbtower.review;

import io.dbtower.registry.RegistryService;
import io.dbtower.review.internal.domain.ReviewRequest;
import io.dbtower.review.internal.domain.ReviewRequest.Status;
import io.dbtower.review.internal.persistence.ReviewRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 변경 티켓 상태 전이는 조건부 UPDATE로만 일어난다 — 실행권은 승인 상태에서 한 번만 얻고, 완료 전이는 실행권을 가진 상태에서만,
 * 끝난 티켓은 다시 실행할 수 없다. JPQL의 enum 경로 리터럴까지 실제 쿼리로 확인한다.
 */
@DataJpaTest
@Import(ChangeTicketGate.class)
class ChangeTicketGatePersistenceTest {

    @Autowired
    ChangeTicketGate gate;

    @Autowired
    ReviewRequestRepository repository;

    @MockitoBean
    RegistryService registry;

    private Long ticket(Status status) {
        ReviewRequest r = new ReviewRequest(1L, "UPDATE t SET a = 1 WHERE id = 1", "사유", "dev", "", null, 1, false, null);
        if (status != Status.PENDING) {
            r.decide(status, "admin", null);
        }
        return repository.saveAndFlush(r).getId();
    }

    private Status status(Long id) {
        return repository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void 실행권은_승인_상태에서_한_번만_얻고_끝난_티켓은_다시_실행할_수_없다() {
        Long id = ticket(Status.APPROVED);

        assertThat(gate.claimExecution(id)).isTrue();
        assertThat(gate.claimExecution(id)).as("두 번째 요청은 실행권을 얻지 못한다").isFalse();

        gate.releaseExecution(id);
        assertThat(status(id)).as("롤백이 확정된 실패는 다시 실행할 수 있다").isEqualTo(Status.APPROVED);

        assertThat(gate.claimExecution(id)).isTrue();
        gate.completeExecution(id, 1L, "admin", 3);
        ReviewRequest executed = repository.findById(id).orElseThrow();
        assertThat(executed.getStatus()).isEqualTo(Status.EXECUTED);
        assertThat(executed.getExecutedBy()).isEqualTo("admin");
        assertThat(executed.getExecutedAt()).isNotNull();
        assertThat(gate.claimExecution(id)).as("실행 완료 티켓은 재실행 불가").isFalse();

        assertThat(gate.claimRollback(id)).isTrue();
        assertThat(gate.claimRollback(id)).isFalse();
        gate.completeRollback(id, 1L, "admin", 3);
        assertThat(status(id)).isEqualTo(Status.ROLLED_BACK);
        assertThat(gate.claimRollback(id)).as("되돌린 티켓은 다시 되돌릴 수 없다").isFalse();
    }

    @Test
    void 대기_반려_티켓은_실행권을_얻지_못한다() {
        assertThat(gate.claimExecution(ticket(Status.PENDING))).isFalse();
        assertThat(gate.claimExecution(ticket(Status.REJECTED))).isFalse();
    }

    @Test
    void 완료_전이는_실행권을_가진_상태에서만_일어난다() {
        Long id = ticket(Status.APPROVED);

        gate.completeExecution(id, 1L, "admin", 1);

        assertThat(status(id)).as("실행권 없이 EXECUTED로 건너뛰지 않는다").isEqualTo(Status.APPROVED);
        assertThat(gate.claimRollback(id)).isFalse();
    }
}
