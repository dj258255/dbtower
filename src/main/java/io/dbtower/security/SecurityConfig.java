package io.dbtower.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 인증·인가 정책 (Phase A1).
 *
 * 두 종류의 주체, 두 종류의 인증:
 * - 사람: 세션 로그인 (폼) + CSRF 쿠키 (SPA가 쿠키를 읽어 헤더로 되돌려주는 표준 패턴)
 * - 기계(MCP 클라이언트·자동화): Bearer 토큰 — 쿠키가 없으므로 CSRF 대상에서 제외
 *
 * 인가 원칙: 진단(조회·explain)은 VIEWER부터, 대상 DB를 바꾸거나 실행하는 행위
 * (등록/삭제/백업/정책)와 토큰 조회는 ADMIN만.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ApiTokenFilter tokenFilter) throws Exception {
        // Bearer 토큰 요청은 쿠키 세션이 없으므로 CSRF 보호 대상이 아니다
        RequestMatcher bearerRequests = request -> {
            String h = request.getHeader("Authorization");
            return h != null && h.startsWith("Bearer ");
        };

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(bearerRequests))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login.html", "/style.css", "/favicon.ico").permitAll()
                        // Prometheus 수집 경로 — 네트워크 레벨 제한 전제 (docs/operations.md)
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/mcp").hasRole("ADMIN")
                        .requestMatchers("/api/security/**").hasRole("ADMIN")
                        .requestMatchers("/api/audit/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/instances").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/instances/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/backup").hasRole("ADMIN")
                        // 세션 종료(kill)는 대상 DB의 실행 세션을 끊는 파괴적 행위 — 백업과 같은 ADMIN 경계
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/sessions/*/kill").hasRole("ADMIN")
                        // 복원 검증도 대상 DB에 임시 DB를 만들고 지우는 행위라 백업과 같은 ADMIN 경계
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/backup/verify").hasRole("ADMIN")
                        // 온라인 스키마 변경(gh-ost, B4)은 실제 테이블 구조를 바꾸는 가장 파괴적 행위 — ADMIN만.
                        // 기본은 dry-run(noop)이지만 execute=true 실행 경로까지 같은 경계로 묶는다.
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/online-ddl").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/instances/*/backup-policy").hasRole("ADMIN")
                        // 파라미터 조회·drift(B6)는 읽기지만 값이 인프라 형상·자격증명을 담고, 마스킹이
                        // 이름 기반 휴리스틱이라 완전하지 않아 ADMIN으로 올린다(ParameterController 주석 참고).
                        .requestMatchers(HttpMethod.GET, "/api/instances/*/parameters").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/param-diff").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login.html?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login.html"))
                // API·MCP 호출은 401(SPA가 로그인으로 보냄), 그 외 브라우저 페이지는 로그인 리다이렉트
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                request -> request.getRequestURI().startsWith("/api/")
                                        || request.getRequestURI().startsWith("/mcp"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login.html"),
                                request -> true));
        return http.build();
    }

    /**
     * CookieCsrfTokenRepository는 토큰이 실제로 "사용"될 때만 쿠키를 쓴다.
     * SPA는 첫 GET부터 쿠키가 필요하므로 매 요청 토큰을 강제로 실체화한다 (Spring Security SPA 권장 패턴).
     */
    static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
