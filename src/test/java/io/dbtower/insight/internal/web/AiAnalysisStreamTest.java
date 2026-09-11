package io.dbtower.insight.internal.web;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 쿼리 상세 AI 분석 스트림의 입구(VERIFICATION 143절) — 범위는 스트림을 열기 전에, 작업 스레드의 실패는 한 번에 받는 경로와 같은 상태 번호로.
 * 대상은 포트 1(연결 거부)이라 AI까지 가지 않는다. 계획이 없으면 AI를 부르지 않는다는 것도 함께 확인된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiAnalysisStreamTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseInstanceRepository instances;

    private DatabaseInstance open;
    private DatabaseInstance teamBeta;

    @BeforeEach
    void seed() {
        open = instances.save(new DatabaseInstance("ai-stream-open", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p"));
        DatabaseInstance beta = new DatabaseInstance("ai-stream-beta", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p");
        beta.updateMeta("beta", null, null, null, null, null);
        teamBeta = instances.save(beta);
    }

    @AfterEach
    void cleanup() {
        instances.deleteAll(List.of(open, teamBeta));
    }

    @Test
    void SELECT가_아니면_작업_스레드에서_400_error_이벤트로_온다() throws Exception {
        String body = stream(open.getId(), "UPDATE t SET v = 1", "\"status\":400");
        assertThat(body).contains("event:error").contains("SELECT").doesNotContain("event:result");
    }

    @Test
    void 대상_조회_실패는_원문_오류_없이_502와_errorId로_온다() throws Exception {
        String body = stream(open.getId(), "SELECT 1", "\"status\":502");
        assertThat(body).contains("errorId=").doesNotContain("Communications link").doesNotContain("event:plan");
    }

    @Test
    void 다른_팀_대상은_스트림을_열기_전에_404() throws Exception {
        mvc.perform(post("/api/instances/" + teamBeta.getId() + "/ai-analysis/stream").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sql\":\"SELECT 1\"}")
                        .with(user("alpha").authorities(new SimpleGrantedAuthority("ROLE_VIEWER"), new SimpleGrantedAuthority("TEAM_alpha"))))
                .andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted());
    }

    private String stream(long id, String sql, String needle) throws Exception {
        MvcResult result = mvc.perform(post("/api/instances/" + id + "/ai-analysis/stream").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sql\":\"" + sql + "\"}")
                        .with(user("viewer-x").roles("VIEWER")))
                .andExpect(request().asyncStarted())
                .andReturn();
        long deadline = System.currentTimeMillis() + 30_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = result.getResponse().getContentAsString();
            if (body.contains(needle)) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("30초 안에 " + needle + "가 오지 않았다. 받은 본문: " + body);
    }
}
