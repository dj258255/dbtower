package io.dbtower.mcp.internal;

import io.dbtower.mcp.internal.DiagnosisService.DiagnosisResult;
import io.dbtower.mcp.internal.DiagnosisService.ToolCallTrace;
import io.dbtower.mcp.internal.domain.Conversation;
import io.dbtower.mcp.internal.domain.ConversationTurn;
import io.dbtower.mcp.internal.persistence.ConversationRepository;
import io.dbtower.mcp.internal.persistence.ConversationTurnRepository;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관제 AI 대화의 계약 — 대화는 만든 사람의 것이고, 다른 사용자·다른 인스턴스의 대화는 미등록과 같은 404다.
 * 턴이 쌓이면 제목이 붙고, 저장되는 도구 호출 기록에는 결과 본문이 없다.
 */
class ConversationServiceTest {

    private static final long INSTANCE = 1L;
    private static final long CONVERSATION = 10L;

    private final RegistryService registry = mock(RegistryService.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ConversationTurnRepository turns = mock(ConversationTurnRepository.class);
    private final ConversationService service = new ConversationService(registry, conversations, turns);
    private final List<ConversationTurn> stored = new ArrayList<>();

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("kim", null, "ROLE_VIEWER"));
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(turns.save(any())).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(turns.findByConversationIdOrderByCreatedAtAscIdAsc(anyLong())).thenAnswer(inv -> List.copyOf(stored));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
        stored.clear();
    }

    private Conversation conversation(long id, String owner, long instanceId, String title) {
        Conversation c = new Conversation(owner, instanceId, title);
        ReflectionTestUtils.setField(c, "id", id);
        when(conversations.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void 다른_사용자의_대화는_존재하지_않는_것처럼_404다() {
        conversation(CONVERSATION, "lee", INSTANCE, "남의 대화");

        assertNotFound(() -> service.detail(INSTANCE, CONVERSATION));
        assertNotFound(() -> service.rename(INSTANCE, CONVERSATION, "제목"));
        assertNotFound(() -> service.delete(INSTANCE, CONVERSATION));
        assertNotFound(() -> service.history(INSTANCE, CONVERSATION));
        assertNotFound(() -> service.appendTurn(INSTANCE, CONVERSATION, "질문", result("답"), 10L));
        verify(conversations, never()).delete(any());
    }

    @Test
    void 다른_인스턴스의_대화에는_그_인스턴스_id로_접근하지_못한다() {
        conversation(CONVERSATION, "kim", 2L, "다른 인스턴스 대화");

        assertNotFound(() -> service.detail(INSTANCE, CONVERSATION));
        verify(turns, never()).save(any());
    }

    @Test
    void 제목은_공백을_걷어낸_1자_이상_120자_이하만_받는다() {
        conversation(CONVERSATION, "kim", INSTANCE, "새 대화");

        assertThatThrownBy(() -> service.rename(INSTANCE, CONVERSATION, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename(INSTANCE, CONVERSATION, "가".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.rename(INSTANCE, CONVERSATION, "  결제 실패 원인  ").title()).isEqualTo("결제 실패 원인");
    }

    @Test
    void 목록은_내_대화만_최신순으로_돌려주고_턴수를_센다() {
        Conversation latest = conversation(30L, "kim", INSTANCE, "최근");
        Conversation older = conversation(20L, "kim", INSTANCE, "예전");
        when(conversations.findByPrincipalAndInstanceIdOrderByUpdatedAtDesc(any(), any(), any()))
                .thenReturn(List.of(latest, older));
        when(turns.countByConversationId(30L)).thenReturn(3L);
        when(turns.countByConversationId(20L)).thenReturn(0L);

        List<ConversationService.ConversationView> list = service.list(INSTANCE);

        assertThat(list).extracting(ConversationService.ConversationView::id).containsExactly(30L, 20L);
        assertThat(list).extracting(ConversationService.ConversationView::turnCount).containsExactly(3L, 0L);
        verify(registry).findById(INSTANCE);
    }

    @Test
    void 첫_턴이_저장되면_제목이_질문_앞_40자로_바뀐다() {
        conversation(CONVERSATION, "kim", INSTANCE, ConversationService.DEFAULT_TITLE);

        service.appendTurn(INSTANCE, CONVERSATION, "결제가 왜 실패해?\n자세히 알려줘", result("확인 중"), 1234L);

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("결제가 왜 실패해? 자세히 알려줘");
    }

    @Test
    void 저장되는_도구_호출_기록에는_결과_본문이_없다() {
        conversation(CONVERSATION, "kim", INSTANCE, "새 대화");
        List<ToolCallTrace> traces = List.of(
                new ToolCallTrace(1, "sessions", "{\"instanceId\":1}", "누가 막고 있나",
                        "SELECT * FROM users WHERE email = 'leak@example.com'", false),
                new ToolCallTrace(2, "kill_session", "{\"pid\":7}", "끊자", "거부됨: 화이트리스트 밖", true));

        service.appendTurn(INSTANCE, CONVERSATION, "지금 뭐가 막혀?", resultWith(traces), 900L);

        ArgumentCaptor<ConversationTurn> saved = ArgumentCaptor.forClass(ConversationTurn.class);
        verify(turns).save(saved.capture());
        String json = saved.getValue().getToolCalls();
        assertThat(json).as("대상 DB에서 온 값은 메타 DB에 쌓지 않는다").doesNotContain("leak@example.com");
        assertThat(json).contains("sessions").contains("kill_session").contains("\"rejected\":true");
        assertThat(json).contains("누가 막고 있나");

        // 다시 읽어 화면에 돌려줄 때도 결과 본문은 없다(왕복이 깨지지 않는지)
        List<ToolCallTrace> roundTrip = service.detail(INSTANCE, CONVERSATION).turns().get(0).toolCalls();
        assertThat(roundTrip).hasSize(2);
        assertThat(roundTrip).extracting(ToolCallTrace::tool).containsExactly("sessions", "kill_session");
        assertThat(roundTrip).allSatisfy(t -> assertThat(t.resultSnippet()).isEmpty());
    }

    @Test
    void 삭제하면_턴도_함께_지운다() {
        conversation(CONVERSATION, "kim", INSTANCE, "새 대화");

        service.delete(INSTANCE, CONVERSATION);

        verify(turns).deleteByConversationId(CONVERSATION);
        verify(conversations).delete(any(Conversation.class));
    }

    @Test
    void history는_오래된_순으로_질문과_답을_넘긴다() {
        conversation(CONVERSATION, "kim", INSTANCE, "새 대화");
        stored.add(new ConversationTurn(CONVERSATION, "첫 질문", "첫 답", null, "high", "mock", "[]", 1L));
        stored.add(new ConversationTurn(CONVERSATION, "둘째 질문", "둘째 답", null, "low", "mock", "[]", 2L));

        List<DiagnosisService.PriorTurn> history = service.history(INSTANCE, CONVERSATION);

        assertThat(history).extracting(DiagnosisService.PriorTurn::question).containsExactly("첫 질문", "둘째 질문");
        assertThat(history).extracting(DiagnosisService.PriorTurn::answer).containsExactly("첫 답", "둘째 답");
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(ConversationService.ConversationNotFound.class);
    }

    private static DiagnosisResult result(String answer) {
        return new DiagnosisResult(true, "mock", "질문", answer, "근본원인", "high", 0, List.of(), null);
    }

    private static DiagnosisResult resultWith(List<ToolCallTrace> traces) {
        return new DiagnosisResult(true, "mock", "질문", "완성된 답", "근본원인", "high", traces.size(), traces, null);
    }
}
