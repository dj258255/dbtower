package io.dbtower.review.internal;

import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.AiAnalyzer.CallSite;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import io.dbtower.review.ChangeTicketGate;
import io.dbtower.review.ReviewSubmittedEvent;
import io.dbtower.review.internal.domain.ReviewRequest;
import io.dbtower.review.internal.persistence.ReviewRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 변경 요청 제출을 흘려 받을 때의 약속(VERIFICATION 146절) — 규칙 판정은 AI 소견보다 먼저, 흘린 조각을 이어 붙이면 저장된 소견과 같고,
 * 한 번에 받는 경로(REST·MCP)는 스트리밍 호출을 쓰지 않는다.
 */
class ReviewServiceStreamTest {

    private static final String SQL = "UPDATE customers SET grade = 'VIP' WHERE id = 3";
    private static final String OPINION = "WHERE id = 3 조건이라 한 건만 바뀝니다. 실행 전에 같은 조건으로 SELECT해 영향 행 수를 확인하세요.";

    private final ReviewRequestRepository repository = mock(ReviewRequestRepository.class);
    private final RegistryService registry = mock(RegistryService.class);
    private final AiAnalyzer ai = mock(AiAnalyzer.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private ReviewService service;

    @BeforeEach
    void setUp() {
        service = new ReviewService(repository, registry, mock(DbmsOperatorFactory.class), ai,
                new QueryMasker(true, false), events, mock(ChangeTicketGate.class), false);
        when(registry.findById(1L)).thenReturn(new DatabaseInstance("mysql", DbmsType.MYSQL, "h", 3306, "sample", "u", "p"));
        // 저장되면 id가 생긴다 — 카드 이벤트가 원시 long id를 받아, id 없는 엔티티를 돌려주면 이벤트 생성에서 NPE가 난다
        when(repository.save(any())).thenAnswer(inv -> {
            ReviewRequest r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 77L);
            return r;
        });
    }

    @Test
    void 흘려_받으면_규칙_판정이_AI보다_먼저_오고_이어_붙인_조각이_저장된_소견과_같다() {
        List<String> order = new ArrayList<>();
        when(ai.completeStreaming(eq(CallSite.REVIEW), anyString(), anyString(), any())).thenAnswer(inv -> {
            order.add("ai-start");
            Consumer<String> onText = inv.getArgument(3);
            onText.accept(OPINION.substring(0, 20));
            onText.accept(OPINION.substring(20));
            return Optional.of(OPINION);
        });
        StringBuilder written = new StringBuilder();

        ReviewRequest saved = service.submit(1L, new ReviewService.SubmitRequest(SQL, "CS-1", null), "req",
                new ReviewService.SubmitListener() {
                    @Override
                    public void findings(List<String> findings, boolean parseLimited) {
                        order.add("findings:" + findings.size());
                    }

                    @Override
                    public void text(String delta) {
                        written.append(delta);
                    }
                });

        assertThat(order.get(0)).startsWith("findings:");
        assertThat(order.get(1)).isEqualTo("ai-start");
        assertThat(saved.getAiOpinion()).isEqualTo(OPINION);
        assertThat(written.toString()).isEqualTo(OPINION);
        ArgumentCaptor<ReviewSubmittedEvent> event = ArgumentCaptor.forClass(ReviewSubmittedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().aiOpinion()).isEqualTo(OPINION);   // 카드에도 흘린 조각이 아니라 완성본이 실린다
        verify(ai, never()).complete(any(), anyString(), anyString());
    }

    @Test
    void 한_번에_받는_제출은_스트리밍_호출을_쓰지_않는다() {
        when(ai.complete(eq(CallSite.REVIEW), anyString(), anyString())).thenReturn(Optional.of(OPINION));

        ReviewRequest saved = service.submit(1L, new ReviewService.SubmitRequest(SQL, "CS-1", null), "req");

        assertThat(saved.getAiOpinion()).isEqualTo(OPINION);
        verify(ai, never()).completeStreaming(any(), anyString(), anyString(), any());
    }
}
