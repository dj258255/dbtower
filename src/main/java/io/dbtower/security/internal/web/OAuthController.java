package io.dbtower.security.internal.web;

import io.dbtower.security.internal.OAuthService;
import io.dbtower.security.internal.domain.OAuthClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * OAuth 2.1 인가 서버 엔드포인트 (MCP Authorization, V20) — MCP 클라이언트가 정적 토큰 대신
 * "브라우저 로그인 → 자동 토큰"을 쓰게 한다. 흐름:
 *
 *  MCP 클라이언트 --(1) /mcp 401 + WWW-Authenticate--> discovery
 *                 --(2) /.well-known/oauth-authorization-server--> 엔드포인트 발견
 *                 --(3) POST /oauth/register (DCR)--> client_id
 *                 --(4) 브라우저로 GET /oauth/authorize--> (미로그인이면 기존 /login.html로 리다이렉트)
 *                 --(5) 로그인 후 authorize가 code를 redirect_uri로 반환-->
 *                 --(6) POST /oauth/token (code + PKCE verifier)--> access/refresh 토큰
 *                 --(7) Authorization: Bearer <access>로 /mcp 호출-->
 *
 * discovery/register/token은 미인증 허용(클라이언트가 로그인 전에 부른다), authorize는 인증 필수
 * (기존 폼 로그인이 처리 — Spring Security가 미로그인 요청을 저장했다가 로그인 후 재생).
 */
@RestController
public class OAuthController {

    private final OAuthService oauth;
    private final String issuer;

    public OAuthController(OAuthService oauth,
                           @Value("${dbtower.base-url:}") String baseUrl,
                           @Value("${server.port:8080}") int port) {
        this.oauth = oauth;
        // issuer = 발급자 식별자이자 모든 엔드포인트의 베이스. base-url이 있으면 그것(리버스 프록시 뒤),
        // 없으면 로컬 포트 — 데모 우선 원칙(datasource 기본값과 같은 결).
        this.issuer = (baseUrl == null || baseUrl.isBlank())
                ? "http://localhost:" + port : baseUrl.replaceAll("/+$", "");
    }

    // ---------- (2) 인가 서버 메타데이터 (RFC 8414) ----------

    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authorizationServerMetadata() {
        return Map.of(
                "issuer", issuer,
                "authorization_endpoint", issuer + "/oauth/authorize",
                "token_endpoint", issuer + "/oauth/token",
                "registration_endpoint", issuer + "/oauth/register",
                "response_types_supported", List.of("code"),
                "grant_types_supported", List.of("authorization_code", "refresh_token"),
                "code_challenge_methods_supported", List.of("S256"),
                "token_endpoint_auth_methods_supported", List.of("none"));
    }

    // ---------- (1) 보호 리소스 메타데이터 (RFC 9728) — /mcp 401이 이 URL을 가리킨다 ----------

    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
                "resource", issuer + "/mcp",
                "authorization_servers", List.of(issuer));
    }

    // ---------- (3) 동적 클라이언트 등록 (RFC 7591) ----------

    public record RegisterRequest(String client_name, List<String> redirect_uris) {
    }

    @PostMapping(value = "/oauth/register", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        OAuthClient client = oauth.register(req.client_name(), req.redirect_uris());
        return ResponseEntity.status(201).body(Map.of(
                "client_id", client.getClientId(),
                "client_name", client.getClientName() == null ? "" : client.getClientName(),
                "redirect_uris", client.redirectUriList(),
                "token_endpoint_auth_method", "none",
                "grant_types", List.of("authorization_code", "refresh_token")));
    }

    // ---------- (4)(5) 인가 — 로그인은 기존 폼 로그인이 이미 강제(SecurityConfig) ----------

    /**
     * (4) 인가 요청 — 로그인된 사용자에게 동의 화면을 보여준다(코드는 여기서 발급하지 않는다).
     *
     * 보안 리뷰 반영 두 가지:
     * - client_id·redirect_uri 화이트리스트 검증을 <b>어떤 리다이렉트보다 먼저</b> 한다(오픈 리다이렉트 방지) —
     *   검증 실패는 리다이렉트로 흘리지 않고 400 직접 응답.
     * - 코드 발급을 GET 응답이 아니라 <b>사용자가 명시적으로 승인하는 POST</b>로 옮긴다(동의 화면). GET만으로
     *   코드를 내주면, 로그인된 사용자가 악성 authorize 링크를 클릭하는 것만으로 공격자 클라이언트에 코드가
     *   발급된다(auth code injection). CSRF 보호되는 승인 폼이 이 CSRF-류 공격을 막는다.
     */
    @GetMapping(value = "/oauth/authorize", produces = MediaType.TEXT_HTML_VALUE)
    public void authorize(@RequestParam("response_type") String responseType,
                          @RequestParam("client_id") String clientId,
                          @RequestParam("redirect_uri") String redirectUri,
                          @RequestParam("code_challenge") String codeChallenge,
                          @RequestParam(value = "code_challenge_method", defaultValue = "S256") String method,
                          @RequestParam(value = "state", required = false) String state,
                          Authentication authentication,
                          org.springframework.security.web.csrf.CsrfToken csrf,
                          HttpServletResponse response) throws IOException {
        try {
            OAuthClient client = oauth.requireClient(clientId, redirectUri); // 화이트리스트 — 먼저, 리다이렉트 전에
            if (!"code".equals(responseType)) {
                // client/redirect는 검증됐으니 리다이렉트 오류가 안전하다
                redirectError(response, redirectUri, "unsupported_response_type", state);
                return;
            }
            renderConsent(response, client, clientId, redirectUri, codeChallenge, method, state,
                    authentication.getName(), csrf);
        } catch (OAuthService.OAuthException e) {
            response.setStatus(400);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"" + e.error() + "\",\"error_description\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * (5) 사용자가 승인 폼을 제출 — 여기서만 코드를 발급한다(CSRF 보호됨: SecurityConfig가 이 POST를
     * CSRF 대상으로 두고, 폼에 토큰이 실린다). 거부(deny)면 error=access_denied로 돌려보낸다.
     */
    @PostMapping("/oauth/authorize/decision")
    public void decision(@RequestParam("client_id") String clientId,
                         @RequestParam("redirect_uri") String redirectUri,
                         @RequestParam("code_challenge") String codeChallenge,
                         @RequestParam("code_challenge_method") String method,
                         @RequestParam(value = "state", required = false) String state,
                         @RequestParam("decision") String decision,
                         Authentication authentication,
                         HttpServletResponse response) throws IOException {
        try {
            oauth.requireClient(clientId, redirectUri); // 폼 위조 대비 재검증
            if (!"approve".equals(decision)) {
                redirectError(response, redirectUri, "access_denied", state);
                return;
            }
            String code = oauth.issueCode(clientId, redirectUri, codeChallenge, method, authentication.getName());
            String sep = redirectUri.contains("?") ? "&" : "?";
            response.sendRedirect(redirectUri + sep + "code=" + enc(code)
                    + (state != null ? "&state=" + enc(state) : ""));
        } catch (OAuthService.OAuthException e) {
            response.setStatus(400);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"" + e.error() + "\",\"error_description\":\"" + e.getMessage() + "\"}");
        }
    }

    /** 동의 화면 — 어떤 클라이언트가 누구로 접근하려는지 보여주고 명시적 승인을 받는다(CSRF 토큰 포함). */
    private void renderConsent(HttpServletResponse response, OAuthClient client, String clientId,
                               String redirectUri, String codeChallenge, String method, String state,
                               String username, org.springframework.security.web.csrf.CsrfToken csrf)
            throws IOException {
        String clientName = client.getClientName() == null || client.getClientName().isBlank()
                ? clientId : client.getClientName();
        String html = """
                <!doctype html><html lang="ko"><head><meta charset="utf-8">
                <title>DBTower 접근 승인</title><link rel="stylesheet" href="/style.css">
                <style>body{display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;
                background:#f4f6f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}
                .card{background:#fff;border-radius:14px;box-shadow:0 8px 30px rgba(0,0,0,.08);padding:36px;max-width:420px}
                h2{margin:0 0 6px;font-size:19px}.muted{color:#6b7280;font-size:13.5px;margin:0 0 22px}
                .row{font-size:14px;margin:10px 0}.k{color:#6b7280}.v{font-weight:600}
                .btns{display:flex;gap:10px;margin-top:26px}button{flex:1;padding:11px;border-radius:8px;border:0;
                font-size:14px;font-weight:600;cursor:pointer}.approve{background:#4f5ef7;color:#fff}
                .deny{background:#eef0f3;color:#374151}</style></head><body>
                <form class="card" method="post" action="/oauth/authorize/decision">
                <h2>접근 승인 요청</h2>
                <p class="muted">아래 애플리케이션이 DBTower MCP 도구에 접근하려 합니다.</p>
                <div class="row"><span class="k">애플리케이션</span> · <span class="v">%s</span></div>
                <div class="row"><span class="k">로그인 계정</span> · <span class="v">%s</span></div>
                <div class="row"><span class="k">콜백</span> · <span class="v">%s</span></div>
                <input type="hidden" name="_csrf" value="%s">
                <input type="hidden" name="client_id" value="%s">
                <input type="hidden" name="redirect_uri" value="%s">
                <input type="hidden" name="code_challenge" value="%s">
                <input type="hidden" name="code_challenge_method" value="%s">
                <input type="hidden" name="state" value="%s">
                <div class="btns">
                <button class="deny" type="submit" name="decision" value="deny">거부</button>
                <button class="approve" type="submit" name="decision" value="approve">승인</button>
                </div></form></body></html>"""
                .formatted(esc(clientName), esc(username), esc(redirectUri), esc(csrf.getToken()),
                        esc(clientId), esc(redirectUri), esc(codeChallenge), esc(method),
                        esc(state == null ? "" : state));
        response.setContentType(MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
        response.getWriter().write(html);
    }

    /** HTML 속성/본문 삽입용 최소 이스케이프 — 사용자·클라이언트 문자열이 그대로 들어가므로 XSS 차단. */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ---------- (6) 토큰 발급/갱신 ----------

    @PostMapping(value = "/oauth/token", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "code_verifier", required = false) String codeVerifier,
            @RequestParam(value = "refresh_token", required = false) String refreshToken) {
        try {
            OAuthService.Tokens tokens = switch (grantType) {
                case "authorization_code" -> oauth.exchangeCode(code, clientId, redirectUri, codeVerifier);
                case "refresh_token" -> oauth.refresh(refreshToken, clientId);
                default -> throw new OAuthService.OAuthException("unsupported_grant_type", grantType);
            };
            return ResponseEntity.ok(Map.of(
                    "access_token", tokens.accessToken(),
                    "token_type", "Bearer",
                    "expires_in", tokens.expiresInSeconds(),
                    "refresh_token", tokens.refreshToken()));
        } catch (OAuthService.OAuthException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.error(), "error_description", e.getMessage()));
        }
    }

    private void redirectError(HttpServletResponse response, String redirectUri, String error, String state)
            throws IOException {
        String sep = redirectUri.contains("?") ? "&" : "?";
        response.sendRedirect(redirectUri + sep + "error=" + enc(error)
                + (state != null ? "&state=" + enc(state) : ""));
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
