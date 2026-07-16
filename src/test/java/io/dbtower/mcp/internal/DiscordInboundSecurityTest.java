package io.dbtower.mcp.internal;

import io.dbtower.mcp.internal.web.DiscordInboundController;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Discord 봇 인바운드 보안 3단계 중 단위로 고정 가능한 두 겹 —
 * (1) Ed25519 요청 서명: 유효 서명만 통과, 변조(본문·타임스탬프)·타 키 서명은 거부.
 * (2) 화이트리스트: 기본 거부(빈 목록 = 전부 거부), 명시된 ID만 통과.
 * 실제 Discord 발사는 앱 등록(외부 계정)이 필요해 라이브 검증은 로컬 서명 시뮬레이션으로 한다.
 */
class DiscordInboundSecurityTest {

    /** RFC 8032 raw 공개키 hex — Java EdECPublicKey에서 리틀엔디언 y + x 홀짝 비트로 재조립 */
    private static String rawPublicKeyHex(EdECPublicKey pub) {
        var point = pub.getPoint();
        byte[] y = point.getY().toByteArray();
        byte[] le = new byte[32];
        for (int i = 0; i < y.length && i < 32; i++) {
            le[i] = y[y.length - 1 - i];   // 빅엔디언 → 리틀엔디언
        }
        if (point.isXOdd()) {
            le[31] |= (byte) 0x80;
        }
        return HexFormat.of().formatHex(le);
    }

    private static String sign(KeyPair pair, String timestamp, byte[] body) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(pair.getPrivate());
        sig.update(timestamp.getBytes(StandardCharsets.UTF_8));
        sig.update(body);
        return HexFormat.of().formatHex(sig.sign());
    }

    @Test
    void 유효_서명은_통과하고_변조는_거부된다() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pubHex = rawPublicKeyHex((EdECPublicKey) pair.getPublic());
        byte[] body = "{\"type\":1}".getBytes(StandardCharsets.UTF_8);
        String ts = "1752624000";
        String sig = sign(pair, ts, body);

        assertTrue(DiscordSignatureVerifier.verify(pubHex, ts, body, sig));
        // 본문 변조 — 서명 불일치
        assertFalse(DiscordSignatureVerifier.verify(pubHex, ts, "{\"type\":2}".getBytes(StandardCharsets.UTF_8), sig));
        // 타임스탬프 변조(리플레이 조작) — 서명 불일치
        assertFalse(DiscordSignatureVerifier.verify(pubHex, "1752624999", body, sig));
    }

    @Test
    void 다른_키의_서명은_거부된다() throws Exception {
        KeyPair real = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] body = "{\"type\":1}".getBytes(StandardCharsets.UTF_8);
        String forged = sign(attacker, "1752624000", body);

        assertFalse(DiscordSignatureVerifier.verify(
                rawPublicKeyHex((EdECPublicKey) real.getPublic()), "1752624000", body, forged));
    }

    @Test
    void 잘못된_키_형식은_예외가_아니라_불일치로_수렴한다() {
        assertFalse(DiscordSignatureVerifier.verify("zz-not-hex", "1", new byte[0], "00"));
        assertFalse(DiscordSignatureVerifier.verify("0011", "1", new byte[0], "00"));
    }

    @Test
    void 화이트리스트는_기본_거부다() {
        // 빈 목록 = 전부 거부 — "설정 안 하면 다 열림"이 아니라 그 반대여야 안전하다
        assertFalse(DiscordInboundController.allowed(Set.of(), "12345"));
        assertTrue(DiscordInboundController.allowed(Set.of("12345"), "12345"));
        assertFalse(DiscordInboundController.allowed(Set.of("12345"), "99999"));
    }
}
