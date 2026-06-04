package io.dbtower.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization: Bearer 토큰 인증 — MCP 클라이언트와 자동화(서비스 계정)용.
 * 토큰이 맞으면 이 요청에 한해 ADMIN 권한을 부여한다 (세션 저장 없음 — stateless).
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    private final ApiTokenProvider tokens;

    public ApiTokenFilter(ApiTokenProvider tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null
                && tokens.matches(header.substring("Bearer ".length()))) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "api-token", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
