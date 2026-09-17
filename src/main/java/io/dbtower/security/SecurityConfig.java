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
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import io.dbtower.security.internal.PlatformRoles;
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
import java.net.URI;

/**
 * 인증·인가 정책 (Phase A1).
 *
 * 두 종류의 주체, 두 종류의 인증:
 * - 사람: 세션 로그인 (폼) + CSRF 쿠키 (SPA가 쿠키를 읽어 헤더로 되돌려주는 표준 패턴)
 * - 기계(MCP 클라이언트·자동화): Bearer 토큰 — 쿠키가 없으므로 CSRF 대상에서 제외
 *
 * 인가 원칙(쓰는 사람 기준, 135절): 관제 조회는 VIEWER부터, 대상 DB의 행 값을 보는 워크벤치와 변경 요청은 REQUESTER부터,
 * 변경 승인은 APPROVER, 대상 DB에 닿는 실행·운영은 OPERATOR, 인스턴스·접속 계정·보안·감사는 ADMIN. 포함 관계는
 * PlatformRoles의 역할 계층이 정한다(ADMIN ⊃ APPROVER·OPERATOR ⊃ REQUESTER ⊃ VIEWER, 승인자와 실행자는 서로를 포함하지 않는다).
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

    /** 역할 계층 — authorizeHttpRequests의 hasRole이 이 빈을 따른다(ADMIN으로 로그인하면 OPERATOR 경로도 통과) */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return PlatformRoles.hierarchy();
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
                // 도구 호출은 호출자 자신의 토큰으로 REST에 위임되고(132절) 역할 판정은 REST가 한다 — 채널 입구에서 ADMIN으로 막을 이유가
                // 사라져, 역할이 있는 사람이면 누구나 자기 역할만큼 에이전트를 쓴다(VIEWER는 관제 도구, REQUESTER부터 워크벤치 도구)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("VIEWER"))
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
                        // 인가 거부(403)는 컨테이너가 /error로 다시 디스패치한다. /error가 인증 대상이면 그 디스패치가
                        // 로그인 리다이렉트(302)로 덮여, 로그인한 VIEWER가 ADMIN 경로를 부르면 "권한 없음" 대신
                        // 로그인 화면 HTML을 받았다(실측, VERIFICATION 128절). 오류 본문엔 스택트레이스가 없다(Boot 기본).
                        .requestMatchers("/error").permitAll()
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
                        // 아래 운영 경로는 대상 DB에 닿거나 운영 형상을 담는다 — DBA 운영(OPERATOR, ADMIN 포함). 예전엔 전부 ADMIN이라
                        // 백업을 돌리는 DBA가 인스턴스 등록·보안 설정 권한까지 함께 가져야 했다(135절)
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/backup").hasRole("OPERATOR")
                        // 세션 종료(kill)는 대상 DB의 실행 세션을 끊는 파괴적 행위 — 백업과 같은 운영 경계
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/sessions/*/kill").hasRole("OPERATOR")
                        // 심층 진단(D9)은 explain(추정)과 달리 대상 DB에서 쿼리를 실제로 실행한다(타임아웃은 걸지만
                        // 워크로드를 돌리는 행위) — 진단이지만 대상에 닿는 운영 경계에 둔다.
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/deep-diagnose").hasRole("OPERATOR")
                        // 복원 검증도 대상 DB에 임시 DB를 만들고 지우는 행위라 백업과 같은 운영 경계
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/backup/verify").hasRole("OPERATOR")
                        // 온라인 스키마 변경(gh-ost, B4)은 실제 테이블 구조를 바꾸는 가장 파괴적 행위 — 운영 경계.
                        // 기본은 dry-run(noop)이지만 execute=true 실행 경로까지 같은 경계로 묶는다.
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/online-ddl").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/instances/*/backup-policy").hasRole("OPERATOR")
                        // 파라미터 조회·drift(B6)는 읽기지만 값이 인프라 형상·자격증명을 담고, 마스킹이
                        // 이름 기반 휴리스틱이라 완전하지 않아 운영 경계로 올린다(ParameterController 주석 참고).
                        .requestMatchers(HttpMethod.GET, "/api/instances/*/parameters").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/param-diff").hasRole("OPERATOR")
                        // 설정 드리프트 이력(B1)도 파라미터 값(old→new)을 담아 같은 운영 경계에 둔다.
                        .requestMatchers(HttpMethod.GET, "/api/instances/*/config-drift", "/api/instances/*/config-drift/around").hasRole("OPERATOR")
                        // 변경 리뷰 승인/반려(B2)는 승인자(APPROVER, ADMIN 포함). 대기함(전 인스턴스 횡단 뷰)도 승인자 트리아지 —
                        // 팀 사용자에게 다른 팀 리뷰 SQL이 새지 않게. 인스턴스별 조회는 authenticated(findById가 LBAC 스코프).
                        .requestMatchers(HttpMethod.POST, "/api/reviews/*/decision").hasRole("APPROVER")
                        .requestMatchers(HttpMethod.GET, "/api/reviews/pending").hasRole("APPROVER")
                        // 변경 요청 제출과 취소는 요청자부터 — 관제만 보는 VIEWER는 대상 DB 변경을 요청하지 않는다
                        // (남의 티켓을 취소할 수 있는지는 서비스가 요청자 본인·승인자·운영자·관리자로 판단한다)
                        // 흘려 받는 경로(146절)는 같은 일을 하는 다른 URL이라 같은 줄에 둔다 — 빠뜨리면 anyRequest(authenticated)로 떨어져 관제도 변경을 요청한다
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/reviews", "/api/instances/*/reviews/stream",
                                "/api/reviews/*/cancel").hasRole("REQUESTER")
                        // 인시던트 리포트(B4)·월간 점검 리포트(B5)는 설정 값·성능을 담아 운영 경계.
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/incident-report", "/api/instances/*/incident-report/stream").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/instances/*/monthly-report").hasRole("OPERATOR")
                        // 워크벤치 콘솔 계정(조회·변경 DB 계정)은 대상 DB 데이터 접근 권한 그 자체라 조회·등록·삭제 전부 ADMIN.
                        .requestMatchers("/api/instances/*/credentials", "/api/instances/*/credentials/*").hasRole("ADMIN")
                        // 마스킹 규칙 변경은 누가 무엇을 볼지 정하는 정책이라 ADMIN. 조회(GET)는 왜 가려졌는지 알 수 있게 연다.
                        .requestMatchers(HttpMethod.POST, "/api/workbench/masking-rules").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/workbench/masking-rules/*").hasRole("ADMIN")
                        // 조회 결과 값을 외부 LLM에 보낼지는 데이터 반출 결정이라 ADMIN만 바꾼다(기본은 막힘)
                        .requestMatchers(HttpMethod.PUT, "/api/workbench/instances/*/settings").hasRole("ADMIN")
                        // 드라이런은 승인자가 판단 근거로, 운영자가 실행 전에 쓴다 — 락을 잡고 문장을 실제로 실행한 뒤 롤백하므로 대상에 닿는 행위다
                        .requestMatchers(HttpMethod.POST, "/api/workbench/tickets/*/dry-run").hasAnyRole("APPROVER", "OPERATOR")
                        // 승인 티켓을 대상 DB에 반영·되돌리고 커밋 불명을 정리하는 경로는 운영자 — 승인한 사람과 실행하는 사람을 나눈다
                        .requestMatchers(HttpMethod.POST, "/api/workbench/tickets/*/execute", "/api/workbench/tickets/*/revert",
                                "/api/workbench/tickets/*/resolve").hasRole("OPERATOR")
                        // 대량 일괄 변경: 시작·멈춤·재개는 실행하는 사람이, 취소는 승인자도 할 수 있다(명세의 권한 표)
                        .requestMatchers(HttpMethod.POST, "/api/workbench/tickets/*/bulk/start",
                                "/api/workbench/tickets/*/bulk/pause",
                                "/api/workbench/tickets/*/bulk/resume").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/workbench/tickets/*/bulk/cancel")
                                .hasAnyRole("OPERATOR", "APPROVER")
                        .requestMatchers(HttpMethod.GET, "/api/workbench/tickets/*/bulk",
                                "/api/workbench/tickets/*/bulk/batches").hasAnyRole("OPERATOR", "APPROVER", "VIEWER")
                        // 워크벤치(조회 계정으로 대상 DB의 행 값 조회·AI 제안·워크시트)는 요청자부터 — 관제 지표와 달리 데이터를 보는 경로다
                        .requestMatchers("/api/workbench/**").hasRole("REQUESTER")
                        // AI 운영 작업(169절) — 릴레이·실행기 경로는 서비스 토큰(ADMIN)만. 사람이 이 경로로 작업 단계를 건너뛰면
                        // 사실 수집 없이 소견이 붙는다. 선점 뒤 단계는 리스 토큰까지 맞아야 해서 ADMIN 사람도 남의 작업을 진행시키지 못한다
                        .requestMatchers(HttpMethod.POST, "/api/ai-operations/outbox/**", "/api/ai-operations/*/claim",
                                "/api/ai-operations/*/facts", "/api/ai-operations/*/retrieving", "/api/ai-operations/*/analyze",
                                "/api/ai-operations/*/fail", "/api/ai-operations/*/notified").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/ai-operations/*/lease-view").hasRole("ADMIN")
                        // 재시도는 모델 호출을 다시 쓰는 운영 판단이라 운영자. 접수·조회·취소는 관제 사용자부터(범위·본인 여부는 서비스가 본다)
                        .requestMatchers(HttpMethod.POST, "/api/ai-operations/*/retry").hasRole("OPERATOR")
                        .requestMatchers("/api/ai-operations", "/api/ai-operations/**").hasRole("VIEWER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        // 저장된 요청(예: OAuth /oauth/authorize)이 있으면 로그인 후 그곳으로 재생하고(OAuth 브라우저 로그인 플로우가
                        // 이 재생에 의존한다), 없으면 역할의 첫 화면으로 간다(RoleHomeSuccessHandler).
                        .successHandler(new RoleHomeSuccessHandler())
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
     * 로그인 뒤 첫 화면을 역할로 고른다(요청자는 워크벤치, 나머지는 대시보드). 저장된 요청이 있으면 그곳으로 재생하되,
     * 저장된 요청이 쿼리 없는 루트("/")면 버린다 — 주소창에 호스트만 치고 들어온 경우까지 모두 대시보드로 가면 역할별 첫 화면이 의미가 없다.
     */
    static final class RoleHomeSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

        private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws ServletException, IOException {
            SavedRequest saved = requestCache.getRequest(request, response);
            if (saved != null && isBareRoot(saved.getRedirectUrl())) {
                requestCache.removeRequest(request, response);
            }
            super.onAuthenticationSuccess(request, response, authentication);
        }

        @Override
        protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) {
            return PlatformRoles.home(authentication.getAuthorities());
        }

        private static boolean isBareRoot(String url) {
            try {
                URI uri = URI.create(url);
                String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
                // Spring Security 6+의 요청 캐시는 저장한 주소에 "continue" 표식을 붙인다 — 표식만 있는 루트도 쿼리 없는 루트로 본다
                String query = uri.getQuery();
                boolean noQuery = query == null || query.isEmpty() || "continue".equals(query);
                return noQuery && ("/".equals(path) || "/index.html".equals(path));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
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
