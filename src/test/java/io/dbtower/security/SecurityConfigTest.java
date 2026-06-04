package io.dbtower.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인가 정책(A1) 회귀 방지 — "누가 무엇을 할 수 있나"를 테스트로 고정한다.
 * 원칙: 진단은 VIEWER부터, 대상 DB를 바꾸는 행위와 토큰 조회는 ADMIN만, 기계는 Bearer 토큰.
 */
@SpringBootTest(properties = "dbtower.security.api-token=test-api-token")
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @Test
    void 미인증_API_호출은_401이다() throws Exception {
        mvc.perform(get("/api/instances")).andExpect(status().isUnauthorized());
    }

    @Test
    void 미인증_페이지_요청은_로그인으로_보낸다() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login.html"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void VIEWER는_조회할_수_있다() throws Exception {
        mvc.perform(get("/api/instances")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void VIEWER는_인스턴스를_등록할_수_없다() throws Exception {
        mvc.perform(post("/api/instances").with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"type\":\"MYSQL\",\"host\":\"h\",\"port\":1,"
                                + "\"dbName\":\"d\",\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void VIEWER는_서비스_토큰을_볼_수_없다() throws Exception {
        mvc.perform(get("/api/security/mcp-token")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ADMIN은_서비스_토큰을_조회한다() throws Exception {
        mvc.perform(get("/api/security/mcp-token")).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("test-api-token"));
    }

    @Test
    void 올바른_Bearer_토큰은_ADMIN으로_인증된다() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer test-api-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void 잘못된_Bearer_토큰은_401이다() throws Exception {
        mvc.perform(get("/api/instances").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 세션_기반_변경_요청은_CSRF_토큰이_없으면_거부된다() throws Exception {
        mvc.perform(post("/api/instances").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void Bearer_토큰_요청은_쿠키가_없으므로_CSRF_없이_동작한다() throws Exception {
        // 403(CSRF 거부)이 아니라 비즈니스 검증까지 도달해야 한다 — 빈 본문이라 4xx여도 403은 아님
        mvc.perform(post("/mcp").header("Authorization", "Bearer test-api-token")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk());
    }
}
