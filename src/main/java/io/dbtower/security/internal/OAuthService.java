package io.dbtower.security.internal;

import io.dbtower.security.internal.domain.OAuthClient;
import io.dbtower.security.internal.domain.OAuthToken;
import io.dbtower.security.internal.persistence.OAuthClientRepository;
import io.dbtower.security.internal.persistence.OAuthTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth 2.1 인가 서버 코어 (V20) — 인가 코드 발급, PKCE 검증, 토큰 교환·갱신.
 *
 * 인가 코드는 60초 일회용이라 메모리에 둔다(ConcurrentHashMap). HA 한계(정직): 다중 노드에서
 * 인가 시작 노드와 토큰 교환 노드가 다르면 코드를 못 찾는다 — 그러나 인가 코드 왕복은 초 단위이고
 * 세션 스티키니스(같은 브라우저→같은 노드)가 일반적이라 실무 영향이 작다. 발급 토큰은 메타 DB에
 * 영속(재시작·다중 노드 생존 — 공유 세션과 같은 이유). refresh 갱신은 회전(옛 토큰 폐기).
 *
 * PKCE(S256)만 허용한다 — 공개 클라이언트라 시크릿이 없고, code_verifier가 곧 소유 증명이다.
 * plain challenge는 받지 않는다(중간자 방어 — OAuth 2.1 권고).
 */
@Service
public class OAuthService {

    /** 인가 코드 수명 — 발급 후 교환까지의 창(RFC 권고 ≤10분, 우리는 짧게 60초). */
    private static final long CODE_TTL_SECONDS = 60;

    private final OAuthClientRepository clientRepository;
    private final OAuthTokenRepository tokenRepository;
    private final SecureRandom random = new SecureRandom();
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    /** code → 발급 컨텍스트(clientId·redirectUri·PKCE 챌린지·사용자·만료). 60초 일회용. */
    private final Map<String, PendingCode> pendingCodes = new ConcurrentHashMap<>();

    public OAuthService(OAuthClientRepository clientRepository, OAuthTokenRepository tokenRepository,
                        @Value("${dbtower.oauth.access-ttl-seconds:3600}") long accessTtlSeconds,
                        @Value("${dbtower.oauth.refresh-ttl-seconds:1209600}") long refreshTtlSeconds) {
        this.clientRepository = clientRepository;
        this.tokenRepository = tokenRepository;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    private record PendingCode(String clientId, String redirectUri, String codeChallenge,
                               String username, LocalDateTime expiresAt) {
    }

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    // ---------- 동적 클라이언트 등록 (RFC 7591) ----------

    @Transactional
    public OAuthClient register(String clientName, List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new OAuthException("invalid_redirect_uri", "redirect_uris가 필요합니다");
        }
        for (String uri : redirectUris) {
            if (!isAllowedRedirect(uri)) {
                throw new OAuthException("invalid_redirect_uri", "허용되지 않는 redirect_uri: " + uri);
            }
        }
        String clientId = "dbt_" + randomToken(24);
        return clientRepository.save(new OAuthClient(clientId, clientName, redirectUris));
    }

    /**
     * redirect_uri 화이트리스트 — 오픈 리다이렉트/인가 코드 탈취 방지(보안 리뷰 반영).
     * 문자열 prefix 검사는 <b>userinfo 우회</b>에 뚫린다: "http://localhost:8080@evil.com/"은
     * startsWith("http://localhost:")를 통과하지만 실제 호스트는 evil.com이다. 그래서 java.net.URI로
     * 파싱해 <b>구조 요소</b>(스킴·호스트)를 검사하고, userinfo·fragment가 있으면 거부한다.
     * 규칙: https(호스트 필수), 루프백 http(127.0.0.1·localhost·[::1]), http/https 아닌 커스텀 스킴(네이티브 앱).
     */
    static boolean isAllowedRedirect(String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        java.net.URI u;
        try {
            u = new java.net.URI(uri);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        if (u.getUserInfo() != null || u.getFragment() != null) {
            return false; // user:pass@host·#frag 트릭 차단
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if ("https".equals(scheme)) {
            return !host.isEmpty();
        }
        if ("http".equals(scheme)) {
            return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
        }
        // 커스텀 스킴(네이티브 앱, 예: cursor://) — http/https는 위에서만 허용(원격 평문 http 차단)
        return scheme.matches("[a-z][a-z0-9+.-]*");
    }

    // ---------- 인가 코드 발급 (PKCE) ----------

    @Transactional(readOnly = true)
    public OAuthClient requireClient(String clientId, String redirectUri) {
        OAuthClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new OAuthException("invalid_client", "등록되지 않은 client_id"));
        if (!client.redirectUriList().contains(redirectUri)) {
            throw new OAuthException("invalid_request", "등록되지 않은 redirect_uri");
        }
        return client;
    }

    /** 로그인된 사용자에게 인가 코드를 발급한다(authorize 엔드포인트가 인증 확인 후 호출). */
    public String issueCode(String clientId, String redirectUri, String codeChallenge,
                            String codeChallengeMethod, String username) {
        if (!"S256".equals(codeChallengeMethod)) {
            throw new OAuthException("invalid_request", "code_challenge_method는 S256만 허용");
        }
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new OAuthException("invalid_request", "code_challenge가 필요합니다(PKCE 필수)");
        }
        String code = randomToken(32);
        pendingCodes.put(code, new PendingCode(clientId, redirectUri, codeChallenge, username,
                LocalDateTime.now().plusSeconds(CODE_TTL_SECONDS)));
        return code;
    }

    // ---------- 토큰 교환 (authorization_code + PKCE) ----------

    @Transactional
    public Tokens exchangeCode(String code, String clientId, String redirectUri, String codeVerifier) {
        PendingCode pending = pendingCodes.remove(code); // 일회용 — 꺼내는 즉시 소진
        if (pending == null || pending.expiresAt().isBefore(LocalDateTime.now())) {
            throw new OAuthException("invalid_grant", "인가 코드가 만료됐거나 유효하지 않습니다");
        }
        if (!pending.clientId().equals(clientId) || !pending.redirectUri().equals(redirectUri)) {
            throw new OAuthException("invalid_grant", "인가 코드의 client/redirect가 일치하지 않습니다");
        }
        if (!verifyPkce(codeVerifier, pending.codeChallenge())) {
            throw new OAuthException("invalid_grant", "PKCE 검증 실패(code_verifier 불일치)");
        }
        return issueTokens(clientId, pending.username());
    }

    @Transactional
    public Tokens refresh(String refreshToken, String clientId) {
        OAuthToken existing = tokenRepository.findByRefreshToken(refreshToken)
                .orElseThrow(() -> new OAuthException("invalid_grant", "유효하지 않은 refresh_token"));
        if (!existing.getClientId().equals(clientId)) {
            throw new OAuthException("invalid_grant", "refresh_token의 client가 일치하지 않습니다");
        }
        tokenRepository.delete(existing); // 회전 — 옛 refresh는 폐기(재사용 감지 기반 방어의 최소형)
        return issueTokens(clientId, existing.getUsername());
    }

    private Tokens issueTokens(String clientId, String username) {
        String access = randomToken(48);
        String refresh = randomToken(48);
        tokenRepository.save(new OAuthToken(access, refresh, clientId, username,
                LocalDateTime.now().plusSeconds(accessTtlSeconds)));
        return new Tokens(access, refresh, accessTtlSeconds);
    }

    // ---------- 토큰 검증 (리소스 서버 = /mcp) ----------

    @Transactional(readOnly = true)
    public Optional<String> validateAccessToken(String accessToken) {
        return tokenRepository.findById(accessToken)
                .filter(t -> !t.expired(LocalDateTime.now()))
                .map(OAuthToken::getUsername);
    }

    /** 만료 토큰 정리 — 재시작·다중 노드에서 쌓이는 죽은 토큰을 주기적으로 비운다. */
    @Scheduled(fixedDelayString = "${dbtower.oauth.cleanup-ms:3600000}")
    @Transactional
    public void cleanupExpired() {
        // refresh TTL만큼 지난 토큰까지 정리 — access 만료 후에도 refresh 유효 기간이 있어 여유를 둔다
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now().minusSeconds(refreshTtlSeconds));
    }

    // ---------- PKCE ----------

    /** S256: BASE64URL(SHA256(verifier)) == challenge. 상수 시간 비교로 타이밍 누출 방지. */
    static boolean verifyPkce(String codeVerifier, String codeChallenge) {
        if (codeVerifier == null || codeChallenge == null) {
            return false;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.US_ASCII),
                    codeChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    private String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** OAuth 오류 응답용 — error 코드(RFC 6749)와 사람이 읽는 설명을 함께 나른다. */
    public static class OAuthException extends RuntimeException {
        private final String error;

        public OAuthException(String error, String description) {
            super(description);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }
}
