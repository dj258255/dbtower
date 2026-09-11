package io.dbtower.review.internal;

import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.RegistryService;
import io.dbtower.review.ChangeTicketGate;
import io.dbtower.review.internal.domain.ReviewRequest;
import io.dbtower.review.internal.persistence.ReviewRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 티켓 취소의 경계 — 요청자 본인이나 ADMIN만, 실행권이 잡힌 티켓은 취소로 풀지 않는다. */
class ReviewServiceCancelTest {

    private final ReviewRequestRepository repository = mock(ReviewRequestRepository.class);
    private final RegistryService registry = mock(RegistryService.class);
    private final ChangeTicketGate gate = mock(ChangeTicketGate.class);
    private ReviewService service;

    @BeforeEach
    void setUp() {
        service = new ReviewService(repository, registry, mock(DbmsOperatorFactory.class), mock(AiAnalyzer.class),
                new QueryMasker(true, false), mock(ApplicationEventPublisher.class), gate, false,
                TransactionOperations.withoutTransaction());
        ReviewRequest review = new ReviewRequest(1L, "UPDATE t SET a = 1 WHERE id = 1", "사유", "dev", "", null, 1, false, null);
        ReflectionTestUtils.setField(review, "id", 7L);
        when(repository.findById(7L)).thenReturn(Optional.of(review));
    }

    @Test
    void 다른_사람의_티켓은_ADMIN이_아니면_취소할_수_없다() {
        assertThrows(AccessDeniedException.class, () -> service.cancel(7L, null, "someone", false));
        verify(gate, never()).cancel(any(), any(), any());
    }

    @Test
    void 요청자는_자기_티켓을_취소하고_메모는_다듬어_남긴다() {
        when(gate.cancel(7L, "dev", "필요 없어짐")).thenReturn(true);

        service.cancel(7L, "  필요 없어짐 ", "dev", false);

        verify(gate).cancel(7L, "dev", "필요 없어짐");
    }

    @Test
    void 실행권이_잡혔거나_끝난_티켓의_취소는_상태_충돌이다() {
        when(gate.cancel(any(), any(), any())).thenReturn(false);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.cancel(7L, null, "admin", true));

        assertTrue(e.getMessage().contains("대기·승인 상태에서만"));
    }
}
