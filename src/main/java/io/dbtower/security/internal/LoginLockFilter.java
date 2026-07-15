package io.dbtower.security.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 잠긴 계정의 로그인 시도를 인증 앞에서 차단한다 (Phase 1). LoginAttemptGuard가 실패를 세어 잠그면,
 * 이 필터가 POST /login 요청을 인증 필터에 닿기 전에 되돌린다 — 잠긴 동안엔 비밀번호가 맞아도 통과 못 한다.
 *
 * 폼 로그인 UX와 축을 맞춰, 차단 시 로그인 페이지로 리다이렉트하되 사유(locked)와 남은 초를 쿼리로 전달한다.
 */
public class LoginLockFilter extends OncePerRequestFilter {

    private final LoginAttemptGuard guard;
    private final String loginProcessingUrl;

    public LoginLockFilter(LoginAttemptGuard guard, String loginProcessingUrl) {
        this.guard = guard;
        this.loginProcessingUrl = loginProcessingUrl;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod())
                && loginProcessingUrl.equals(request.getServletPath())) {
            String username = request.getParameter("username");
            if (guard.isLocked(username)) {
                response.sendRedirect("/login.html?error=locked&retryAfter="
                        + guard.lockRemainingSeconds(username));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
