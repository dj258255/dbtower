package io.dbtower.security.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * OAuth Bearer 액세스 토큰 인증 (V20) — MCP 클라이언트가 브라우저 로그인으로 받은 토큰을 검증한다.
 *
 * 정적 api-token(ApiTokenFilter)과 공존한다: 토큰이 oauth_token 테이블에 있고 만료 전이면, 발급받은
 * 사용자의 <b>실제 권한을 platform_user에서 재조회</b>해 부여한다(정적 토큰의 고정 ADMIN과 달리
 * 사용자별 역할·팀 스코프가 그대로 반영되고, 권한 변경도 즉시 반영). 세션은 만들지 않는다(stateless).
 */
@Component
public class OAuthTokenFilter extends OncePerRequestFilter {

    private final OAuthService oauth;
    private final UserDetailsService userDetailsService;

    public OAuthTokenFilter(OAuthService oauth, UserDetailsService userDetailsService) {
        this.oauth = oauth;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            oauth.validateAccessToken(header.substring("Bearer ".length()))
                    .ifPresent(username -> authenticate(username, request));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String username, HttpServletRequest request) {
        try {
            UserDetails user = userDetailsService.loadUserByUsername(username);
            var auth = new UsernamePasswordAuthenticationToken(username, null, user.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (UsernameNotFoundException e) {
            // 토큰은 유효하나 사용자가 삭제됨 — 인증하지 않는다(익명으로 진행, /mcp는 401로 귀결)
        }
    }
}
