package io.dbtower.workbench.internal.web;

import io.dbtower.operator.OperatorException;
import io.dbtower.workbench.internal.WorkbenchAssistant;
import io.dbtower.workbench.internal.WorkbenchAssistant.AssistantRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * 워크벤치 AI 보조 스트림의 작업 스레드 실패 — 스키마 조회 실패 같은 대상 DB 원문 오류는 errorId로만(148절 감사, CWE-209).
 * 거절(WorkbenchRejection)의 사유 문장은 사람에게 보여주려고 만든 것이라 그대로 간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantStreamErrorTest {

    private static final String RAW = "ORA-12514: TNS listener host=10.1.2.3 service=PAYPROD";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WorkbenchAssistant assistant;

    @Test
    void 대상_조회_실패는_원문_없이_502와_errorId로_온다() throws Exception {
        when(assistant.ask(anyLong(), any(AssistantRequest.class), any(WorkbenchAssistant.StreamListener.class)))
                .thenThrow(new OperatorException(RAW));
        String body = stream("\"status\":502");
        assertThat(body).contains("event:error").contains("errorId=").doesNotContain("10.1.2.3").doesNotContain("PAYPROD");
    }

    @Test
    void 그_밖의_실패도_원문_없이_500과_errorId로_온다() throws Exception {
        when(assistant.ask(anyLong(), any(AssistantRequest.class), any(WorkbenchAssistant.StreamListener.class)))
                .thenThrow(new RuntimeException("NullPointerException at /opt/dbtower/internal " + RAW));
        String body = stream("\"status\":500");
        assertThat(body).contains("errorId=").doesNotContain("/opt/dbtower").doesNotContain("10.1.2.3");
    }

    private String stream(String needle) throws Exception {
        MvcResult result = mvc.perform(post("/api/workbench/worksheets/1/assistant/stream").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"어제 결제 실패 건\"}")
                        .with(user("requester-x").roles("REQUESTER")))
                .andExpect(request().asyncStarted())
                .andReturn();
        long deadline = System.currentTimeMillis() + 30_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (body.contains(needle)) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("30초 안에 " + needle + "가 오지 않았다. 받은 본문: " + body);
    }
}
