package io.dbtower.security.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OAuth 2.1 코어 순수 로직 — PKCE(S256) 검증과 redirect_uri 화이트리스트.
 * 발급·교환 수명주기는 라이브 e2e(VERIFICATION — curl로 전체 브라우저 플로우)가 맡는다.
 */
class OAuthServiceTest {

    private static String s256(String verifier) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void PKCE는_올바른_verifier만_통과한다() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String challenge = s256(verifier);
        assertTrue(OAuthService.verifyPkce(verifier, challenge));
        // 틀린 verifier — 소유 증명 실패
        assertFalse(OAuthService.verifyPkce("wrong-verifier", challenge));
        // null 방어
        assertFalse(OAuthService.verifyPkce(null, challenge));
        assertFalse(OAuthService.verifyPkce(verifier, null));
    }

    @Test
    void redirect_uri는_루프백_https_커스텀스킴만_허용한다() {
        // MCP 클라이언트 콜백은 통상 루프백
        assertTrue(OAuthService.isAllowedRedirect("http://127.0.0.1:52100/callback"));
        assertTrue(OAuthService.isAllowedRedirect("http://localhost:8976/oauth/callback"));
        assertTrue(OAuthService.isAllowedRedirect("https://app.example.com/cb"));
        assertTrue(OAuthService.isAllowedRedirect("cursor://anysphere.cursor-mcp/oauth"));
        // 원격 평문 http는 거부 — 피싱 콜백으로 인가 코드가 새는 것 방지
        assertFalse(OAuthService.isAllowedRedirect("http://evil.example.com/steal"));
        assertFalse(OAuthService.isAllowedRedirect(""));
        assertFalse(OAuthService.isAllowedRedirect(null));
    }
}
