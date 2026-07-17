package io.dbtower.mcp.internal;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slack v0 서명 검증 — 유효 서명 통과, 본문·타임스탬프 변조 거부, 리플레이 창(5분) 거부를 고정한다.
 * (Discord Ed25519 검증과 같은 자리의 관문 — 실 워크스페이스 없이 로컬 서명 시뮬레이션이 검증 범위)
 */
class SlackSignatureVerifierTest {

    private static String sign(String secret, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "v0=" + HexFormat.of().formatHex(
                mac.doFinal(("v0:" + ts + ":" + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 유효_서명은_통과한다() throws Exception {
        long now = 1_700_000_000L;
        String body = "{\"type\":\"url_verification\",\"challenge\":\"abc\"}";
        String sig = sign("secret1", String.valueOf(now), body);
        assertTrue(SlackSignatureVerifier.verify("secret1", String.valueOf(now), body, sig, now));
    }

    @Test
    void 본문이나_키가_다르면_거부한다() throws Exception {
        long now = 1_700_000_000L;
        String sig = sign("secret1", String.valueOf(now), "body");
        assertFalse(SlackSignatureVerifier.verify("secret1", String.valueOf(now), "tampered", sig, now));
        assertFalse(SlackSignatureVerifier.verify("other", String.valueOf(now), "body", sig, now));
    }

    @Test
    void 리플레이_창을_지난_타임스탬프는_거부한다() throws Exception {
        long then = 1_700_000_000L;
        String sig = sign("secret1", String.valueOf(then), "body");
        assertFalse(SlackSignatureVerifier.verify("secret1", String.valueOf(then), "body", sig, then + 301));
        assertTrue(SlackSignatureVerifier.verify("secret1", String.valueOf(then), "body", sig, then + 299));
    }
}
