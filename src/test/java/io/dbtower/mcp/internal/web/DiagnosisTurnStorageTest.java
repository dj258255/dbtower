package io.dbtower.mcp.internal.web;

import io.dbtower.mcp.internal.DiagnosisService;
import io.dbtower.mcp.internal.DiagnosisService.DiagnosisListener;
import io.dbtower.mcp.internal.DiagnosisService.DiagnosisResult;
import io.dbtower.mcp.internal.persistence.ConversationRepository;
import io.dbtower.mcp.internal.persistence.ConversationTurnRepository;
import io.dbtower.operator.OperatorException;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 진단이 끝나지 않았으면 대화에 아무것도 남기지 않는다 — AI 비활성·답 없음·예외는 "턴"이 아니다.
 * 남기면 다음 진단의 맥락에 답 없는 질문이 사실처럼 실린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiagnosisTurnStorageTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseInstanceRepository instances;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationTurnRepository turns;

    @MockitoBean
    DiagnosisService diagnosisService;

    private DatabaseInstance instance;

    @BeforeEach
    void seed() {
        conversations.deleteAll();
        turns.deleteAll();
        instance = instances.save(new DatabaseInstance("conv-store", DbmsType.MYSQL, "127.0.0.1", 3306, "d", "u", "p"));
    }

    @AfterEach
    void cleanup() {
        turns.deleteAll();
        conversations.deleteAll();
        instances.delete(instance);
    }

    private long createConversation() throws Exception {
        return conversations.save(new io.dbtower.mcp.internal.domain.Conversation("user", instance.getId(), "새 대화"))
                .getId();
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void AI가_비활성이면_턴을_남기지_않는다() throws Exception {
        when(diagnosisService.diagnose(anyLong(), anyString(), anyString(), anyString(), anyList(),
                any(DiagnosisListener.class)))
                .thenReturn(new DiagnosisResult(false, "off", "질문", null, null, "none", 0, List.of(), "비활성"));
        long id = createConversation();

        mvc.perform(post("/api/instances/" + instance.getId() + "/diagnose").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"왜 느려?\",\"conversationId\":" + id + "}"))
                .andExpect(status().isOk());

        assertThat(turns.countByConversationId(id)).isZero();
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 진단이_예외로_끝나면_턴을_남기지_않는다() throws Exception {
        when(diagnosisService.diagnose(anyLong(), anyString(), anyString(), anyString(), anyList(),
                any(DiagnosisListener.class)))
                .thenThrow(new OperatorException("대상 DB 조회 실패"));
        long id = createConversation();

        mvc.perform(post("/api/instances/" + instance.getId() + "/diagnose").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"왜 느려?\",\"conversationId\":" + id + "}"))
                .andExpect(status().isBadGateway());

        assertThat(turns.countByConversationId(id)).isZero();
    }

    /**
     * AI 백엔드가 실패하면 aiEnabled=true인데 답이 null인 결과가 온다(사유는 note에만 있다).
     * 이걸 저장하면 답 없는 턴이 맥락에 "앞 질문"으로 실린다 — 이 묶음이 없애려던 모양이다.
     */
    @Test
    @WithMockUser(roles = "VIEWER")
    void AI가_답을_못_내면_턴을_남기지_않는다() throws Exception {
        when(diagnosisService.diagnose(anyLong(), anyString(), anyString(), anyString(), anyList(),
                any(DiagnosisListener.class)))
                .thenReturn(new DiagnosisResult(true, "mock", "질문", null, null, "low", 0, List.of(),
                        "AI가 응답을 반환하지 못했습니다"));
        long id = createConversation();

        mvc.perform(post("/api/instances/" + instance.getId() + "/diagnose").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"왜 느려?\",\"conversationId\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiEnabled").value(true));

        assertThat(turns.countByConversationId(id)).isZero();
    }

    /**
     * 스트림은 답을 이미 흘려보낸 뒤다 — 턴 저장이 실패해도(진단 도중 대화가 지워진 경우) error 이벤트를 보내면
     * 화면은 "답을 받고 나서 실패"를 보게 된다. 로그에만 남기고 조용히 끝난다.
     */
    @Test
    @WithMockUser(roles = "VIEWER")
    void 스트리밍_중_대화가_지워져도_error_이벤트를_보내지_않는다() throws Exception {
        long id = createConversation();
        when(diagnosisService.diagnose(anyLong(), anyString(), anyString(), anyString(), anyList(),
                any(DiagnosisListener.class)))
                .thenAnswer(invocation -> {
                    conversations.deleteById(id);   // 진단이 도는 사이 사용자가 대화를 지웠다
                    return new DiagnosisResult(true, "mock", "왜 느려?", "답", "원인", "high", 0, List.of(), null);
                });

        MvcResult result = mvc.perform(post("/api/instances/" + instance.getId() + "/diagnose/stream").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"왜 느려?\",\"conversationId\":" + id + "}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = awaitEvent(result, "event:result");
        assertThat(body).as("답은 전달됐다").contains("event:result");
        assertThat(body).as("저장 실패를 화면에 오류로 알리지 않는다").doesNotContain("event:error");
        assertThat(turns.countByConversationId(id)).isZero();
    }

    /** SSE 본문은 MockMvc가 ISO-8859-1로 읽는다 — 진단 스트림 테스트와 같은 방식으로 UTF-8로 읽는다. */
    private String awaitEvent(MvcResult result, String needle) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (body.contains(needle)) {
                // 스트림은 result 직후 끝난다 — 뒤따를 이벤트가 있으면 여기서 본문에 붙는다
                Thread.sleep(500);
                return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("30초 안에 " + needle + "가 오지 않았다. 받은 본문: " + body);
    }
}
