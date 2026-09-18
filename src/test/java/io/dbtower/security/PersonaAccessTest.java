package io.dbtower.security;

import io.dbtower.security.internal.domain.PlatformUser;
import io.dbtower.security.internal.persistence.PlatformUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 쓰는 사람 기준 역할(VERIFICATION 135절)의 인가 행렬 — 누가 어느 입구로 들어와 무엇까지 할 수 있나를 고정한다.
 *
 * <p>허용 쪽은 존재하지 않는 id로 불러 인가만 통과했는지(401·403이 아닌지) 본다. 대상 DB나 실제 티켓에 닿지 않고도
 * "필터가 막았는가"만 가를 수 있다. 승인자와 운영자가 서로의 일을 못 하는 것이 이 설계의 핵심이라 양쪽 모두 거부를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PersonaAccessTest {

    private static final String PASSWORD = "persona-pass-1234";
    private static final long NONE = 999_999L;
    private static final List<String> SEEDED = List.of("persona-requester", "persona-operator", "persona-new");

    @Autowired
    MockMvc mvc;

    @Autowired
    PlatformUserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seedUsers() {
        removeUsers();
        users.save(new PlatformUser("persona-requester", encoder.encode(PASSWORD), PlatformUser.Role.REQUESTER));
        users.save(new PlatformUser("persona-operator", encoder.encode(PASSWORD), PlatformUser.Role.OPERATOR));
    }

    @AfterEach
    void removeUsers() {
        SEEDED.forEach(name -> users.findByUsername(name).ifPresent(users::delete));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void 관제는_지표를_보지만_워크벤치와_변경_요청에는_들어가지_못한다() throws Exception {
        mvc.perform(get("/api/instances")).andExpect(status().isOk());
        mvc.perform(get("/api/workbench/instances")).andExpect(status().isForbidden());
        mvc.perform(submitReview()).andExpect(status().isForbidden());
        mvc.perform(post("/api/reviews/" + NONE + "/cancel").with(csrf())).andExpect(status().isForbidden());
        // 흘려 받는 제출도 같은 경계 — URL이 달라 matcher에서 빠지면 authenticated로 떨어진다(146절)
        mvc.perform(stream("/reviews/stream", "{\"sql\":\"UPDATE t SET v = 1 WHERE id = 1\"}")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "REQUESTER")
    void 흘려_받는_제출은_요청자부터_인시던트_리포트_스트림은_운영자부터() throws Exception {
        mvc.perform(stream("/reviews/stream", "{\"sql\":\"UPDATE t SET v = 1 WHERE id = 1\"}")).andExpect(passesAuthorization());
        mvc.perform(stream("/incident-report/stream?from=2026-09-11T00:00&to=2026-09-11T01:00", "")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void 운영자는_인시던트_리포트를_흘려_받는다() throws Exception {
        mvc.perform(stream("/incident-report/stream?from=2026-09-11T00:00&to=2026-09-11T01:00", "")).andExpect(passesAuthorization());
    }

    private static MockHttpServletRequestBuilder stream(String path, String body) {
        return post("/api/instances/" + NONE + path).with(csrf()).contentType("application/json").content(body);
    }

    @Test
    @WithMockUser(roles = "REQUESTER")
    void 요청자는_워크벤치와_변경_요청까지만_한다() throws Exception {
        mvc.perform(get("/api/workbench/instances")).andExpect(status().isOk());
        mvc.perform(submitReview()).andExpect(passesAuthorization());
        mvc.perform(decide()).andExpect(status().isForbidden());
        mvc.perform(get("/api/reviews/pending")).andExpect(status().isForbidden());
        mvc.perform(ticket("dry-run", "{}")).andExpect(status().isForbidden());
        mvc.perform(ticket("execute", "{}")).andExpect(status().isForbidden());
        mvc.perform(kill()).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "APPROVER")
    void 승인자는_승인과_드라이런까지_하고_실행하지_못한다() throws Exception {
        mvc.perform(get("/api/workbench/instances")).andExpect(status().isOk());
        mvc.perform(decide()).andExpect(passesAuthorization());
        mvc.perform(get("/api/reviews/pending")).andExpect(status().isOk());
        mvc.perform(ticket("dry-run", "{}")).andExpect(passesAuthorization());
        mvc.perform(ticket("execute", "{}")).andExpect(status().isForbidden());
        mvc.perform(ticket("revert", "{\"dryRun\":true}")).andExpect(status().isForbidden());
        mvc.perform(ticket("resolve", "{\"applied\":true,\"note\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(kill()).andExpect(status().isForbidden());
        mvc.perform(get("/api/security/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void 운영자는_실행과_대상_운영을_하고_승인하지_못한다() throws Exception {
        mvc.perform(ticket("dry-run", "{}")).andExpect(passesAuthorization());
        mvc.perform(ticket("execute", "{}")).andExpect(passesAuthorization());
        mvc.perform(ticket("revert", "{\"dryRun\":true}")).andExpect(passesAuthorization());
        mvc.perform(kill()).andExpect(passesAuthorization());
        mvc.perform(post("/api/instances/" + NONE + "/backup").with(csrf())).andExpect(passesAuthorization());
        mvc.perform(decide()).andExpect(status().isForbidden());
        mvc.perform(get("/api/reviews/pending")).andExpect(status().isForbidden());
        mvc.perform(post("/api/instances").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/instances/" + NONE + "/credentials")).andExpect(status().isForbidden());
        mvc.perform(get("/api/security/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자는_승인과_실행_양쪽의_입구를_모두_통과한다() throws Exception {
        mvc.perform(decide()).andExpect(passesAuthorization());
        mvc.perform(ticket("execute", "{}")).andExpect(passesAuthorization());
        mvc.perform(kill()).andExpect(passesAuthorization());
        mvc.perform(get("/api/security/users")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void 역할_계층_밖의_권한은_MCP_채널에_들어가지_못한다() throws Exception {
        mvc.perform(post("/mcp").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void MCP_채널은_관제부터_열린다() throws Exception {
        // 인가를 통과하면 컨트롤러가 Bearer 헤더를 요구한다(401) — 채널 입구가 403으로 막지 않았다는 것만 본다.
        // 도구가 무엇까지 되는지는 호출자 토큰으로 위임된 REST가 가른다(132절)
        mvc.perform(post("/mcp").contentType("application/json").content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    @WithMockUser(authorities = {"TEAM_team-a", "ROLE_APPROVER"})
    void me는_팀_권한이_앞에_있어도_대표_역할과_능력을_돌려준다() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("APPROVER"))
                .andExpect(jsonPath("$.home").value("/"))
                .andExpect(jsonPath("$.capabilities", hasItems("WORKBENCH", "CHANGE_REQUEST", "CHANGE_APPROVE", "CHANGE_DRY_RUN")))
                .andExpect(jsonPath("$.capabilities", not(hasItem("CHANGE_EXECUTE"))));
    }

    @Test
    void 요청자는_로그인하면_워크벤치로_운영자는_대시보드로_간다() throws Exception {
        // 저장된 요청이 있을 때의 재생·루트 무시는 RoleHomeSuccessHandlerTest가, 실제 쿠키 세션 흐름은 라이브 검증(135절)이 본다
        mvc.perform(login("persona-requester")).andExpect(redirectedUrl("/?mode=workbench"));
        mvc.perform(login("persona-operator")).andExpect(redirectedUrl("/"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자는_역할을_지정해_사용자를_만들고_바꾸며_목록에는_비밀번호가_없다() throws Exception {
        mvc.perform(createUser("persona-new", "APPROVER")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("APPROVER"));
        mvc.perform(createUser("persona-new", "APPROVER")).andExpect(status().isConflict());
        mvc.perform(get("/api/security/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'persona-new')].role", hasItem("APPROVER")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("passwordHash").doesNotContain("$2a$"));

        mvc.perform(patch("/api/security/users/persona-new/role").with(csrf())
                        .contentType("application/json").content("{\"role\":\"OPERATOR\"}"))
                .andExpect(status().isOk());
        assertThat(users.findByUsername("persona-new").orElseThrow().getRole()).isEqualTo(PlatformUser.Role.OPERATOR);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void 관리자가_아니면_사용자를_만들거나_역할을_바꿀_수_없다() throws Exception {
        mvc.perform(createUser("persona-new", "ADMIN")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/security/users/persona-operator/role").with(csrf())
                        .contentType("application/json").content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        assertThat(users.findByUsername("persona-new")).isEmpty();
        assertThat(users.findByUsername("persona-operator").orElseThrow().getRole()).isEqualTo(PlatformUser.Role.OPERATOR);
    }

    private static ResultMatcher passesAuthorization() {
        return result -> assertThat(result.getResponse().getStatus()).as("인가 통과(401·403이 아니어야 한다)").isNotIn(401, 403);
    }

    private static MockHttpServletRequestBuilder submitReview() {
        return post("/api/instances/" + NONE + "/reviews").with(csrf())
                .contentType("application/json").content("{\"sql\":\"UPDATE t SET a = 1 WHERE id = 1\"}");
    }

    private static MockHttpServletRequestBuilder decide() {
        return post("/api/reviews/" + NONE + "/decision").with(csrf())
                .contentType("application/json").content("{\"approved\":true}");
    }

    private static MockHttpServletRequestBuilder ticket(String action, String body) {
        return post("/api/workbench/tickets/" + NONE + "/" + action).with(csrf()).contentType("application/json").content(body);
    }

    private static MockHttpServletRequestBuilder kill() {
        return post("/api/instances/" + NONE + "/sessions/1/kill").with(csrf());
    }

    private static MockHttpServletRequestBuilder createUser(String username, String role) {
        return post("/api/security/users").with(csrf()).contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"" + role + "\"}");
    }

    private static MockHttpServletRequestBuilder login(String username) {
        return post("/login").with(csrf()).param("username", username).param("password", PASSWORD);
    }
}
