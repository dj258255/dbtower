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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실시간 세션 스트림의 입구(VERIFICATION 140절) — 스코프는 구독 순간 요청 주체로 가르고, 막힌 대상도 조용히 비우지 않는다.
 *
 * <p>대상은 포트 1(연결 거부)이다. 대상 DB 없이도 "관제가 붙으면 프레임이 흐르고, 실패는 ERROR 프레임으로 보인다"를
 * 확인할 수 있고, 삭제하면 채널이 GONE으로 닫히는 경로까지 함께 탄다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LiveSessionStreamTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseInstanceRepository instances;

    private DatabaseInstance open;
    private DatabaseInstance teamBeta;

    @BeforeEach
    void seed() {
        open = instances.save(new DatabaseInstance("live-open", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p"));
        DatabaseInstance beta = new DatabaseInstance("live-beta", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p");
        beta.updateMeta("beta", null, null, null, null, null);
        teamBeta = instances.save(beta);
    }

    @AfterEach
    void cleanup() {
        instances.deleteAll(List.of(open, teamBeta));
    }

    @Test
    void 관제가_붙으면_이벤트_스트림으로_프레임이_흐르고_대상_실패는_ERROR로_보인다() throws Exception {
        MvcResult result = mvc.perform(get("/api/instances/" + open.getId() + "/live/sessions")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(user("live-viewer").roles("VIEWER")))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = awaitBody(result, "\"status\":\"ERROR\"");
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(body).contains("event:frame").contains("\"instanceId\":" + open.getId()).contains("\"seq\":1");
        // 접속 계정은 프레임에 실리지 않는다
        assertThat(body).doesNotContain("\"password\"").doesNotContain("\"username\":\"u\"");
    }

    @Test
    void 다른_팀_대상은_존재하지_않는_것과_같게_404로_막는다() throws Exception {
        mvc.perform(get("/api/instances/" + teamBeta.getId() + "/live/sessions")
                        .with(user("alpha-viewer").authorities(
                                new SimpleGrantedAuthority("ROLE_VIEWER"), new SimpleGrantedAuthority("TEAM_alpha"))))
                .andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted());
    }

    @Test
    void 로그인하지_않으면_스트림이_열리지_않는다() throws Exception {
        MvcResult result = mvc.perform(get("/api/instances/" + open.getId() + "/live/sessions"))
                .andExpect(request().asyncNotStarted())
                .andReturn();
        assertThat(result.getResponse().getStatus()).isIn(302, 401);
    }

    private static String awaitBody(MvcResult result, String needle) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = result.getResponse().getContentAsString();
            if (body.contains(needle)) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("20초 안에 " + needle + "가 오지 않았다. 받은 본문: " + body);
    }
}
