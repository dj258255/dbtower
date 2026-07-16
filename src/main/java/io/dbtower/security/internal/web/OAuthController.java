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

    @GetMapping("/oauth/authorize")
    public void authorize(@RequestParam("response_type") String responseType,
                          @RequestParam("client_id") String clientId,
                          @RequestParam("redirect_uri") String redirectUri,
                          @RequestParam("code_challenge") String codeChallenge,
                          @RequestParam(value = "code_challenge_method", defaultValue = "S256") String method,
                          @RequestParam(value = "state", required = false) String state,
                          Authentication authentication,
                          HttpServletResponse response) throws IOException {
        // 여기 닿았다면 이미 인증됨(authorize는 SecurityConfig에서 authenticated() — 미로그인은 로그인 페이지로).
        if (!"code".equals(responseType)) {
            redirectError(response, redirectUri, "unsupported_response_type", state);
            return;
        }
        try {
            oauth.requireClient(clientId, redirectUri); // client_id·redirect_uri 화이트리스트 검증
            String code = oauth.issueCode(clientId, redirectUri, codeChallenge, method, authentication.getName());
            String sep = redirectUri.contains("?") ? "&" : "?";
            String location = redirectUri + sep + "code=" + enc(code)
                    + (state != null ? "&state=" + enc(state) : "");
            response.sendRedirect(location);
        } catch (OAuthService.OAuthException e) {
            // client/redirect가 검증 안 된 단계의 오류는 리다이렉트로 흘리지 않고 직접 응답(오픈 리다이렉트 방지)
            response.setStatus(400);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"" + e.error() + "\",\"error_description\":\"" + e.getMessage() + "\"}");
        }
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
