package io.dbtower.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인가 정책(A1) 회귀 방지 — "누가 무엇을 할 수 있나"를 테스트로 고정한다.
 * 원칙: 관제 조회는 VIEWER부터, 토큰 조회·인스턴스 등록은 ADMIN만, 기계는 Bearer 토큰.
 * 사람별 역할(요청자·승인자·운영자)의 행렬은 PersonaAccessTest가 본다.
 */
@SpringBootTest(properties = {"dbtower.security.api-token=test-api-token", "dbtower.security.mcp-token=test-mcp-token"})
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
    void VIEWER는_승인_티켓을_대상_DB에_닿게_할_수_없다() throws Exception {
        // 드라이런도 문장을 실제로 실행한 뒤 롤백하고 락을 잡으므로 승인자·운영자 경계다 — 관제는 어느 쪽도 아니다
        for (String path : new String[]{"/api/workbench/tickets/1/dry-run", "/api/workbench/tickets/1/execute",
                "/api/workbench/tickets/1/revert"}) {
            mvc.perform(post(path).with(csrf()).contentType("application/json").content("{\"dryRun\":true}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void VIEWER는_대량_일괄_변경을_시작하거나_멈출_수_없다() throws Exception {
        for (String path : new String[]{"/api/workbench/tickets/1/bulk/start", "/api/workbench/tickets/1/bulk/pause",
                "/api/workbench/tickets/1/bulk/resume", "/api/workbench/tickets/1/bulk/cancel"}) {
            mvc.perform(post(path).with(csrf()).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(roles = "APPROVER")
    void APPROVER는_대량_일괄_변경을_취소만_할_수_있다() throws Exception {
        // 승인한 사람은 멈춰 세울 수는 있어야 하지만, 시작·재개는 실행하는 사람의 몫이다(명세의 권한 표)
        for (String path : new String[]{"/api/workbench/tickets/1/bulk/start", "/api/workbench/tickets/1/bulk/resume"}) {
            mvc.perform(post(path).with(csrf()).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        // 취소는 권한을 통과해 서비스까지 간다(진행 중인 실행이 없어 404) — 403이 아니라는 것이 요점이다
        mvc.perform(post("/api/workbench/tickets/1/bulk/cancel").with(csrf()))
                .andExpect(status().is(not(403)));
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
                .andExpect(jsonPath("$.token").value("test-mcp-token"));   // MCP 카드는 MCP 전용 토큰을 준다(#99)
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
        mvc.perform(post("/mcp").header("Authorization", "Bearer test-mcp-token")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk());
    }

    // #99 — 두 서비스 토큰은 자리가 다르다. API 토큰은 /mcp에서, MCP 토큰은 도구가 부르지 않는 REST에서 인증되지 않는다

    @Test
    void API_토큰으로는_MCP_채널에_들어가지_못한다() throws Exception {
        mvc.perform(post("/mcp").header("Authorization", "Bearer test-api-token")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void MCP_토큰은_도구가_부르는_조회_경로에서만_통한다() throws Exception {
        mvc.perform(get("/api/instances").header("Authorization", "Bearer test-mcp-token")).andExpect(status().isOk());
        // 도구가 부르지 않는 관리 API — 토큰이 새도 열리지 않는다
        mvc.perform(get("/api/security/mcp-token").header("Authorization", "Bearer test-mcp-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/security/users").header("Authorization", "Bearer test-mcp-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/instances").header("Authorization", "Bearer test-mcp-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/workbench/tickets/1/execute").header("Authorization", "Bearer test-mcp-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void API_토큰은_지금처럼_REST에서_통한다() throws Exception {
        mvc.perform(get("/api/security/mcp-token").header("Authorization", "Bearer test-api-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("test-mcp-token"));   // 카드는 MCP 전용 토큰을 준다
    }
}
