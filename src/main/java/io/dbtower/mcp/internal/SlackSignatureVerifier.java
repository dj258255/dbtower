package io.dbtower.mcp.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Slack 요청 서명 검증(v0 HMAC-SHA256) — Discord의 Ed25519와 같은 자리의 관문.
 * basestring = "v0:{timestamp}:{body}", 서명 = "v0=" + hex(HMAC_SHA256(signing secret, basestring)).
 * 타임스탬프가 5분 이상 어긋나면 리플레이로 간주해 거부한다(Slack 공식 가이드).
 */
public final class SlackSignatureVerifier {

    private static final long MAX_SKEW_SECONDS = 300;

    private SlackSignatureVerifier() {
    }

    public static boolean verify(String signingSecret, String timestamp, String body,
                                 String signature, long nowEpochSeconds) {
        if (signingSecret == null || signingSecret.isBlank()
                || timestamp == null || signature == null || body == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowEpochSeconds - ts) > MAX_SKEW_SECONDS) {
            return false; // 리플레이 방어
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
            String expected = "v0=" + HexFormat.of().formatHex(digest);
            // 타이밍 공격 방어 — 단순 equals 대신 상수 시간 비교
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
