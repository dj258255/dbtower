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
 * Authorization: Bearer 서비스 토큰 인증 — 자동화(API 토큰)와 MCP 클라이언트(MCP 토큰)용.
 * 토큰이 맞으면 이 요청에 한해 ADMIN 권한을 부여한다 (세션 저장 없음 — stateless).
 *
 * <p>두 토큰은 자리가 다르다(#99): API 토큰은 /mcp를 뺀 REST, MCP 토큰은 /mcp와 MCP 도구가 부르는 REST 경로({@link McpTokenScope})만.
 * 자리가 아니면 인증하지 않고 넘긴다 — 뒤 체인이 미인증으로 거부한다(401·로그인 리다이렉트).
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    private final ApiTokenProvider tokens;
    private final McpTokenProvider mcpTokens;

    public ApiTokenFilter(ApiTokenProvider tokens, McpTokenProvider mcpTokens) {
        this.tokens = tokens;
        this.mcpTokens = mcpTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String candidate = header.substring("Bearer ".length());
            String path = request.getRequestURI().substring(request.getContextPath().length());
            String principal = null;
            if (tokens.matches(candidate) && !McpTokenScope.isMcpEndpoint(path)) {
                principal = "api-token";
            } else if (mcpTokens.matches(candidate) && McpTokenScope.allows(request.getMethod(), path)) {
                principal = "mcp-token";
            }
            if (principal != null) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            }
        }
        chain.doFilter(request, response);
    }
}
