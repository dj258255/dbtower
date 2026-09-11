package io.dbtower.mcp.internal.web;

import io.dbtower.mcp.internal.DiagnosisService;
import io.dbtower.mcp.internal.DiagnosisService.DiagnosisListener;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * 진단 스트림의 작업 스레드 실패는 한 번에 받는 경로(GlobalExceptionHandler)와 같은 규칙으로 — 대상 DB 원문 오류는 화면에 흘리지 않는다.
 * 148절 감사 전에는 e.getMessage()를 그대로 error 이벤트에 실어, 드라이버 문장(호스트·스키마)이 브라우저까지 갔다(CWE-209).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiagnosisStreamErrorTest {

    private static final String RAW = "Communications link failure host=10.1.2.3 schema=payments_prod";

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseInstanceRepository instances;

    @MockitoBean
    DiagnosisService diagnosisService;

    private DatabaseInstance instance;

    @BeforeEach
    void seed() {
        instance = instances.save(new DatabaseInstance("diag-stream-err", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p"));
    }

    @AfterEach
    void cleanup() {
        instances.delete(instance);
    }

    @Test
    void 대상_조회_실패는_원문_없이_502와_errorId로_온다() throws Exception {
        when(diagnosisService.diagnose(anyLong(), anyString(), anyString(), anyString(), any(DiagnosisListener.class)))
                .thenThrow(new OperatorException(RAW));
        String body = stream("\"status\":502");
        assertThat(body).contains("event:error").contains("errorId=").doesNotContain("10.1.2.3").doesNotContain("payments_prod");
    }

    @Test
    void 그_밖의_실패도_원문_없이_500과_errorId로_온다() throws Exception {
        when(diagnosisService.diagnose(anyLong(), anyString(), anyString(), anyString(), any(DiagnosisListener.class)))
                .thenThrow(new RuntimeException("claude CLI 실패: /Users/ops/.claude/credentials " + RAW));
        String body = stream("\"status\":500");
        assertThat(body).contains("errorId=").doesNotContain("credentials").doesNotContain("10.1.2.3");
    }

    private String stream(String needle) throws Exception {
        MvcResult result = mvc.perform(post("/api/instances/" + instance.getId() + "/diagnose/stream").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"왜 느려졌어?\"}")
                        .with(user("viewer-x").roles("VIEWER")))
                .andExpect(request().asyncStarted())
                .andReturn();
        long deadline = System.currentTimeMillis() + 30_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            // SSE 본문은 MockMvc가 ISO-8859-1로 읽는다 — 한글 메시지를 보려면 UTF-8로 읽는다(146절)
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (body.contains(needle)) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("30초 안에 " + needle + "가 오지 않았다. 받은 본문: " + body);
    }
}
