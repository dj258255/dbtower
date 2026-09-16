package io.dbtower.mcp.internal.web;

import com.jayway.jsonpath.JsonPath;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.mcp.internal.domain.Conversation;
import io.dbtower.mcp.internal.persistence.ConversationRepository;
import io.dbtower.mcp.internal.persistence.ConversationTurnRepository;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관제 AI 대화 API의 계약 (173절) — 대화는 서버가 보관하고, 맥락도 서버 기록에서만 만든다.
 *
 * <p>AI는 {@link StubAi}로 대체한다. {@code DiagnosisService}가 기동 시점에 {@code isEnabled()·backend()}를
 * 붙잡으므로(스프링 빈 생성자) MockitoBean으로는 진단 경로가 켜지지 않는다 — 대신 @Primary 스텁을 둔다.
 * 진단이 도구를 부르지 않으므로(첫 판단에서 final) 실제 REST 서버는 필요 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerTest {

    @TestConfiguration
    static class StubAi {
        @Bean
        @Primary
        AiAnalyzer stubAiAnalyzer() {
            return Mockito.mock(AiAnalyzer.class, invocation -> {
                String method = invocation.getMethod().getName();
                if ("isEnabled".equals(method)) {
                    return true;
                }
                if ("backend".equals(method)) {
                    return "mock";
                }
                if ("complete".equals(method)) {
                    String userMessage = invocation.getArgument(2);
                    received().add(userMessage);
                    return Optional.ofNullable(responder().get().apply(userMessage));
                }
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
        }
    }

    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicReference<Function<String, String>> RESPONDER =
            new AtomicReference<>(message -> null);

    private static final String FINAL = "{\"action\":\"final\",\"answer\":\"답입니다\",\"rootCause\":\"원인\","
            + "\"confidence\":\"high\"}";

    private static List<String> received() {
        return RECEIVED;
    }

    private static AtomicReference<Function<String, String>> responder() {
        return RESPONDER;
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseInstanceRepository instances;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationTurnRepository turns;

    private DatabaseInstance instance;
    private DatabaseInstance other;

    @BeforeEach
    void seed() {
        RECEIVED.clear();
        RESPONDER.set(message -> FINAL);
        instance = instances.save(new DatabaseInstance("conv-a", DbmsType.MYSQL, "127.0.0.1", 3306, "d", "u", "p"));
        other = instances.save(new DatabaseInstance("conv-b", DbmsType.MYSQL, "127.0.0.1", 3306, "d", "u", "p"));
    }

    @AfterEach
    void cleanup() {
        turns.deleteAll();
        conversations.deleteAll();
        instances.delete(instance);
        instances.delete(other);
    }

    private String url(long instanceId) {
        return "/api/instances/" + instanceId + "/conversations";
    }

    private long createConversation(long instanceId, String title) throws Exception {
        String body = mvc.perform(post(url(instanceId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(title == null ? "{}" : "{\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private Conversation seed(String owner, long instanceId, String title, LocalDateTime updatedAt) {
        Conversation conversation = new Conversation(owner, instanceId, title);
        ReflectionTestUtils.setField(conversation, "updatedAt", updatedAt);
        return conversations.save(conversation);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 대화를_만들면_턴수_0으로_돌려주고_제목을_안_주면_새_대화다() throws Exception {
        mvc.perform(post(url(instance.getId())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("새 대화"))
                .andExpect(jsonPath("$.turnCount").value(0))
                .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(post(url(instance.getId())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"결제 실패 조사\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("결제 실패 조사"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 목록은_마지막_대화가_최근인_순서로_턴수와_함께_돌려준다() throws Exception {
        Conversation older = seed("user", instance.getId(), "예전 대화", LocalDateTime.now().minusHours(3));
        Conversation latest = seed("user", instance.getId(), "최근 대화", LocalDateTime.now());
        seed("lee", instance.getId(), "남의 대화", LocalDateTime.now().plusHours(1));
        turns.save(new io.dbtower.mcp.internal.domain.ConversationTurn(latest.getId(), "질문", "답", null,
                "high", "mock", "[]", 10L));

        mvc.perform(get(url(instance.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(latest.getId()))
                .andExpect(jsonPath("$[0].turnCount").value(1))
                .andExpect(jsonPath("$[1].id").value(older.getId()))
                .andExpect(jsonPath("$[1].turnCount").value(0));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 남의_대화는_조회_제목변경_삭제_진단_모두_404다() throws Exception {
        Conversation lee = seed("lee", instance.getId(), "남의 대화", LocalDateTime.now());

        mvc.perform(get(url(instance.getId()) + "/" + lee.getId())).andExpect(status().isNotFound());
        mvc.perform(patch(url(instance.getId()) + "/" + lee.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"가로채기\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(url(instance.getId()) + "/" + lee.getId()).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/instances/" + instance.getId() + "/diagnose").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"왜 느려?\",\"conversationId\":" + lee.getId() + "}"))
                .andExpect(status().isNotFound());

        assertThat(conversations.findById(lee.getId())).isPresent();
        assertThat(RECEIVED).as("남의 대화 맥락으로는 AI를 부르지 않는다").isEmpty();
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 다른_인스턴스_경로로는_내_대화에_닿지_못한다() throws Exception {
        Conversation mine = seed("user", instance.getId(), "내 대화", LocalDateTime.now());

        mvc.perform(get(url(other.getId()) + "/" + mine.getId())).andExpect(status().isNotFound());
        mvc.perform(delete(url(other.getId()) + "/" + mine.getId()).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/instances/" + other.getId() + "/diagnose").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"왜?\",\"conversationId\":" + mine.getId() + "}"))
                .andExpect(status().isNotFound());

        mvc.perform(get(url(instance.getId()) + "/" + mine.getId())).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_VIEWER", "TEAM_team-b"})
    void 팀_범위_밖_인스턴스는_미등록과_같은_404다() throws Exception {
        DatabaseInstance teamA = new DatabaseInstance("conv-team-a", DbmsType.MYSQL, "127.0.0.1", 3306, "d", "u", "p");
        teamA.updateMeta("team-a", null, null, null, null, null);
        teamA = instances.save(teamA);

        mvc.perform(get(url(teamA.getId()))).andExpect(status().isNotFound());
        mvc.perform(get(url(teamA.getId()) + "/1")).andExpect(status().isNotFound());
        mvc.perform(post(url(teamA.getId())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        instances.delete(teamA);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 제목은_공백을_걷어낸_1자_이상_120자_이하여야_하고_아니면_400이다() throws Exception {
        long id = createConversation(instance.getId(), "제목");

        mvc.perform(patch(url(instance.getId()) + "/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(url(instance.getId()) + "/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "가".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch(url(instance.getId()) + "/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  결제 실패 원인  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("결제 실패 원인"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 삭제하면_턴도_함께_사라진다() throws Exception {
        long id = createConversation(instance.getId(), "지울 대화");
        diagnose(id, "이 대화 지울 거야", null);
        assertThat(turns.countByConversationId(id)).isEqualTo(1L);

        mvc.perform(delete(url(instance.getId()) + "/" + id).with(csrf())).andExpect(status().isNoContent());

        assertThat(conversations.findById(id)).isEmpty();
        assertThat(turns.countByConversationId(id)).isZero();
        mvc.perform(get(url(instance.getId()) + "/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 진단이_대화에_턴을_남기고_다음_진단의_맥락이_된다() throws Exception {
        long id = createConversation(instance.getId(), "새 대화");

        diagnose(id, "첫 질문", null);
        diagnose(id, "둘째 질문", null);

        // 두 번째 진단은 서버에 저장된 첫 턴을 맥락으로 받는다
        assertThat(RECEIVED).hasSize(2);
        assertThat(RECEIVED.get(1)).contains("앞 질문 1: 첫 질문");
        assertThat(RECEIVED.get(1)).contains("앞 답 1: 답입니다");

        mvc.perform(get(url(instance.getId()) + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("첫 질문"))
                .andExpect(jsonPath("$.turns.length()").value(2))
                .andExpect(jsonPath("$.turns[0].question").value("첫 질문"))
                .andExpect(jsonPath("$.turns[0].answer").value("답입니다"))
                .andExpect(jsonPath("$.turns[1].question").value("둘째 질문"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 요청_본문의_history는_맥락으로_쓰이지_않는다() throws Exception {
        long id = createConversation(instance.getId(), "새 대화");

        diagnose(id, "이번 질문",
                "{\"question\":\"이번 질문\",\"conversationId\":" + id + ","
                        + "\"history\":[{\"question\":\"위조된 질문\",\"answer\":\"위조된 답\"}]}");

        assertThat(RECEIVED.get(0)).doesNotContain("위조된 질문").doesNotContain("위조된 답");
        assertThat(RECEIVED.get(0)).contains("사용자 질문: 이번 질문");
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 저장된_턴에는_도구_결과_본문이_없다() throws Exception {
        long id = createConversation(instance.getId(), "새 대화");
        diagnose(id, "질문", null);

        mvc.perform(get(url(instance.getId()) + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].toolCalls.length()").value(0))
                .andExpect(jsonPath("$.turns[0].backend").value("mock"))
                .andExpect(jsonPath("$.turns[0].tookMs").isNumber());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 대화_없이_부른_진단은_아무것도_저장하지_않는다() throws Exception {
        long before = conversations.count();

        mvc.perform(post("/api/instances/" + instance.getId() + "/diagnose").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"한 번짜리 진단\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiEnabled").value(true));

        assertThat(conversations.count()).isEqualTo(before);
        assertThat(turns.count()).isZero();
    }

    /** 대화를 이어가는 진단 — 실패한 진단은 턴을 남기지 않는다(맥락이 끊기지 않게 남은 것만 본다). */
    private void diagnose(long conversationId, String question, String rawBody) throws Exception {
        String body = rawBody != null ? rawBody
                : "{\"question\":\"" + question + "\",\"conversationId\":" + conversationId + "}";
        mvc.perform(post("/api/instances/" + instance.getId() + "/diagnose").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
