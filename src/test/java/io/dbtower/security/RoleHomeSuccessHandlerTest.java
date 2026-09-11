package io.dbtower.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 뒤 어디로 가나 — 저장된 요청의 재생과 역할별 첫 화면의 우선순위.
 *
 * <p>저장은 프레임워크의 HttpSessionRequestCache로 한다(ExceptionTranslationFilter가 쓰는 것과 같은 저장 형식이라, 저장 주소에 붙는
 * "continue" 표식까지 실제와 같다 — 트레이스로 확인: {@code Saved request http://localhost/workbench.html?ticket=3&continue}).
 */
class RoleHomeSuccessHandlerTest {

    private final SecurityConfig.RoleHomeSuccessHandler handler = new SecurityConfig.RoleHomeSuccessHandler();

    @Test
    void 저장된_요청이_있으면_역할의_첫_화면보다_그곳으로_간다() throws Exception {
        // OAuth 브라우저 로그인과 알림 딥링크가 이 재생에 기댄다 — 역할별 첫 화면이 이것을 덮으면 안 된다
        MockHttpSession session = sessionWithSavedRequest("/workbench.html", "ticket=3");
        assertThat(loginAs("ROLE_OPERATOR", session)).startsWith("http://localhost/workbench.html?ticket=3");
    }

    @Test
    void 호스트만_치고_들어온_요청자는_저장된_루트를_버리고_워크벤치로_간다() throws Exception {
        assertThat(loginAs("ROLE_REQUESTER", sessionWithSavedRequest("/", null))).isEqualTo("/workbench.html");
        assertThat(loginAs("ROLE_REQUESTER", sessionWithSavedRequest("/index.html", null))).isEqualTo("/workbench.html");
    }

    @Test
    void 쿼리가_있는_루트는_딥링크라_그대로_재생한다() throws Exception {
        // 대시보드 딥링크(?instance=..&compareAt=..)는 루트지만 사용자가 가려던 곳이다
        MockHttpSession session = sessionWithSavedRequest("/", "instance=2&compareAt=2026-09-11T04:00:00");
        assertThat(loginAs("ROLE_REQUESTER", session)).startsWith("http://localhost/?instance=2&compareAt=");
    }

    @Test
    void 저장된_요청이_없으면_역할의_첫_화면이다() throws Exception {
        assertThat(loginAs("ROLE_REQUESTER", new MockHttpSession())).isEqualTo("/workbench.html");
        assertThat(loginAs("ROLE_APPROVER", new MockHttpSession())).isEqualTo("/");
    }

    private static MockHttpSession sessionWithSavedRequest(String path, String query) {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest original = new MockHttpServletRequest("GET", path);
        original.setQueryString(query);
        original.setSession(session);
        new HttpSessionRequestCache().saveRequest(original, new MockHttpServletResponse());
        return session;
    }

    private String loginAs(String role, MockHttpSession session) throws Exception {
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/login");
        login.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = new UsernamePasswordAuthenticationToken("u", null, AuthorityUtils.createAuthorityList(role));
        handler.onAuthenticationSuccess(login, response, auth);
        return response.getRedirectedUrl();
    }
}
