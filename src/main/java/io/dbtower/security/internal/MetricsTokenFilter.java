package io.dbtower.security.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Prometheus 스크레이프 경로(/actuator/prometheus) 선택적 토큰 보호 (Phase 1).
 *
 * 토큰이 설정되면 `Authorization: Bearer <token>`(또는 `?token=`)이 맞아야 통과한다. 미설정이면
 * 현행대로 열어두되(permitAll) 기동 시 WARN으로 "네트워크 레벨 제한 전제"임을 알린다 —
 * 침묵하는 개방보다 알려진 개방이 낫다. 메트릭 토큰은 API/MCP 토큰과 별개(스크레이퍼 전용, 최소 권한).
 */
public class MetricsTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsTokenFilter.class);
    private static final String PATH = "/actuator/prometheus";

    private final String token;

    public MetricsTokenFilter(String token) {
        this.token = (token == null) ? "" : token.trim();
        if (this.token.isEmpty()) {
            log.warn("dbtower.metrics.token 미설정 — /actuator/prometheus가 인증 없이 열려 있습니다. "
                    + "네트워크 레벨 제한을 전제로 하거나 토큰을 설정하세요(docs/operations.md)");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!token.isEmpty() && PATH.equals(request.getServletPath())) {
            if (!matches(presented(request))) {
                // sendError는 컨테이너 에러 디스패치(/error)를 타 로그인 리다이렉트(302)가 되므로,
                // 스크레이퍼가 이해하는 깨끗한 401을 직접 쓴다.
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("metrics token required");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String presented(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length());
        }
        return request.getParameter("token");  // 일부 스크레이퍼는 헤더 대신 쿼리 파라미터를 쓴다
    }

    /** 타이밍 공격 방지 상수 시간 비교 */
    private boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
