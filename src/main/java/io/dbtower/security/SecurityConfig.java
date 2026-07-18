package io.dbtower.security;

import io.dbtower.security.internal.LoginAttemptGuard;
import io.dbtower.security.internal.LoginLockFilter;
import io.dbtower.security.internal.MetricsTokenFilter;
import jakarta.servlet.FilterChain;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.core.annotation.Order;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import io.dbtower.security.internal.OAuthTokenFilter;


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

    /**
     * TLS 종단(리버스 프록시) 뒤에 둘 때 쿠키에 Secure 플래그를 붙인다 (Phase 1).
     * 세션 쿠키는 server.servlet.session.cookie.secure로, CSRF 쿠키는 여기서 — 둘 다 같은 스위치.
     * 기본 false(평문 HTTP 개발/데모). 프록시로 HTTPS 종단 시 dbtower.security.cookie-secure=true.
     */
    @Value("${dbtower.security.cookie-secure:false}")
    private boolean cookieSecure;

    /** Prometheus 스크레이프 경로 보호 토큰(선택). 미설정이면 /actuator/prometheus는 현행대로 열림. */
    @Value("${dbtower.metrics.token:}")
    private String metricsToken;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private CookieCsrfTokenRepository csrfCookieRepository() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookieCustomizer(c -> c.secure(cookieSecure));
        return repo;
    }

    /** 외부 접근 주소(리버스 프록시 뒤면 그것, 없으면 로컬 포트) — OAuth resource_metadata URL의 베이스. */
    @Value("${dbtower.base-url:}")
    private String baseUrl;

    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * /mcp 미인증 응답 — 401 + WWW-Authenticate: Bearer resource_metadata="...".
     * MCP 클라이언트는 이 헤더를 보고 protected-resource 메타데이터를 따라가 OAuth 로그인 플로우를
     * 자동으로 시작한다(RFC 9728 / MCP Authorization). 헤더가 없으면 클라이언트는 그냥 정적 토큰이
     * 없다고 판단하고 멈춘다 — 이 한 줄이 "브라우저 로그인 창이 뜨는" 흐름의 방아쇠다.
     */
    private AuthenticationEntryPoint mcpAuthEntryPoint() {
        String base = (baseUrl == null || baseUrl.isBlank())
                ? "http://localhost:" + serverPort : baseUrl.replaceAll("/+$", "");
        String metadata = base + "/.well-known/oauth-protected-resource";
        return (request, response, authException) -> {
            // setStatus(sendError 아님) — sendError는 컨테이너 에러 디스패치를 유발해 요청이 필터를
            // 다시 타고(폼 로그인 체인으로) 302 리다이렉트로 덮이던 실측 함정. setStatus는 그대로 커밋된다.
            response.setHeader("WWW-Authenticate",
                    "Bearer resource_metadata=\"" + metadata + "\"");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        };
    }

    /**
     * MCP 전용 필터 체인 (@Order(1)) — /mcp는 stateless 리소스 서버라 브라우저 앱 체인과 분리한다.
     * 폼 로그인·세션·CSRF가 없어야 미인증 응답이 302 로그인 리다이렉트가 아니라 <b>깨끗한 401 +
     * WWW-Authenticate</b>가 되고, 그래야 MCP 클라이언트가 resource_metadata를 따라가 OAuth
     * discovery를 시작한다. 인증은 두 Bearer 필터(정적 api-token / OAuth 액세스 토큰)가 담당.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain mcpFilterChain(HttpSecurity http, ApiTokenFilter tokenFilter,
                                              OAuthTokenFilter oauthTokenFilter)
            throws Exception {
        http
                .securityMatcher("/mcp")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(oauthTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .exceptionHandling(e -> e.authenticationEntryPoint(mcpAuthEntryPoint()));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http, ApiTokenFilter tokenFilter,
                                           OAuthTokenFilter oauthTokenFilter,
                                           LoginAttemptGuard loginAttemptGuard) throws Exception {
        // Bearer 토큰 요청은 쿠키 세션이 없으므로 CSRF 보호 대상이 아니다
        RequestMatcher bearerRequests = request -> {
            String h = request.getHeader("Authorization");
            return h != null && h.startsWith("Bearer ");
        };

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfCookieRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // 봇 인바운드는 외부 서버(Discord)가 호출 — 세션·CSRF 대신 Ed25519 서명이 인증
                        .ignoringRequestMatchers("/api/inbound/discord", "/api/inbound/slack")
                        // OAuth 등록·토큰은 외부 MCP 클라이언트가 쿠키 없이 호출(공개 클라이언트) — CSRF 제외.
                        // authorize는 브라우저 GET이라 CSRF 대상이 아니고, 인가 코드+PKCE가 위조를 막는다.
                        .ignoringRequestMatchers("/oauth/register", "/oauth/token")
                        .ignoringRequestMatchers(bearerRequests))
                // Prometheus 스크레이프 경로 선택적 토큰 보호(미설정이면 통과 + 기동 WARN)
                .addFilterBefore(new MetricsTokenFilter(metricsToken),
                        UsernamePasswordAuthenticationFilter.class)
                // 잠긴 계정의 로그인 시도는 인증 앞에서 차단(브루트포스 방어)
                .addFilterBefore(new LoginLockFilter(loginAttemptGuard, "/login"),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(oauthTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // 로그인 화면도 브랜드 아이콘을 보여주므로 파비콘 자산 일체를 미인증 허용(민감정보 아님)
                        .requestMatchers("/login.html", "/style.css",
                                "/favicon.ico", "/favicon.svg", "/favicon-96x96.png", "/apple-touch-icon.png").permitAll()
                        // Prometheus 수집 경로 — 네트워크 레벨 제한 전제 (docs/operations.md)
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        // OAuth discovery·등록·토큰은 미인증 허용(클라이언트가 로그인 전에 부른다).
                        // authorize는 authenticated() — 미로그인이면 기존 폼 로그인으로 유도되고, 로그인 후 재생된다.
                        .requestMatchers("/.well-known/oauth-authorization-server",
                                "/.well-known/oauth-protected-resource",
                                "/oauth/register", "/oauth/token").permitAll()
                        .requestMatchers("/oauth/authorize").authenticated()
                        // 봇 인바운드 — 인증은 요청 서명(Ed25519)+채널·유저 화이트리스트가 담당(컨트롤러에서 검증).
                        // 공개키 미설정이면 컨트롤러가 404로 기능 자체를 숨긴다(기능 게이트)
                        .requestMatchers("/api/inbound/discord", "/api/inbound/slack").permitAll()
                        .requestMatchers("/api/security/**").hasRole("ADMIN")
                        .requestMatchers("/api/audit/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/instances").hasRole("ADMIN")
                        // 멱등 등록(upsert) — IaC 프로비저닝이 쓰는 경로. 등록/삭제와 같은 ADMIN 경계
                        .requestMatchers(HttpMethod.PUT, "/api/instances").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/instances/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/backup").hasRole("ADMIN")
                        // 세션 종료(kill)는 대상 DB의 실행 세션을 끊는 파괴적 행위 — 백업과 같은 ADMIN 경계
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/sessions/*/kill").hasRole("ADMIN")
                        // 심층 진단(D9)은 explain(추정)과 달리 대상 DB에서 쿼리를 실제로 실행한다(타임아웃은 걸지만
                        // 워크로드를 돌리는 행위) — "실행하는 행위는 ADMIN" 원칙에 따라 진단이지만 ADMIN 경계에 둔다.
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/deep-diagnose").hasRole("ADMIN")
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
                        // 설정 드리프트 이력(B1)도 파라미터 값(old→new)을 담아 같은 ADMIN 경계에 둔다.
                        .requestMatchers(HttpMethod.GET, "/api/instances/*/config-drift", "/api/instances/*/config-drift/around").hasRole("ADMIN")
                        // 변경 리뷰 승인/반려(B2)는 ADMIN. 제출·조회는 authenticated(자기 팀 인스턴스는 LBAC가 스코프).
                        .requestMatchers(HttpMethod.POST, "/api/reviews/*/decision").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        // alwaysUse=false: 저장된 요청(예: OAuth /oauth/authorize)이 있으면 로그인 후 그곳으로
                        // 재생하고, 없으면 "/". OAuth 브라우저 로그인 플로우가 이 재생에 의존한다.
                        .defaultSuccessUrl("/", false)
                        .failureUrl("/login.html?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login.html"))
                // /api/ 미인증은 순수 401(SPA가 로그인으로 보냄), 그 외 브라우저 페이지
                //   (예: /oauth/authorize)는 로그인 리다이렉트. /mcp는 전용 체인(@Order 1)이 처리.
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                request -> request.getRequestURI().startsWith("/api/"))
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
