package io.dbtower.mcp.internal;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.HexFormat;

/**
 * Discord Interactions 요청 서명 검증 (Ed25519) — 봇 인바운드의 1차 방어선.
 *
 * Discord는 모든 인터랙션 요청에 X-Signature-Ed25519(서명)·X-Signature-Timestamp를 싣고,
 * 검증 대상은 timestamp || rawBody다. 서명이 틀린 요청에 200을 주면 Discord가 엔드포인트
 * 등록 자체를 거부한다(검증 실패 요청에 401을 주는지 등록 시 테스트함) — 즉 이 검증은
 * 보안이자 프로토콜 요구사항이다.
 *
 * 공개키는 Discord 개발자 포털의 애플리케이션 공개키(hex 32바이트) — Java 표준 EdDSA(15+)로
 * 검증하고 외부 암호 라이브러리를 더하지 않는다. raw 키의 마지막 바이트 최상위 비트가 x의
 * 홀짝, 나머지가 리틀엔디언 y라는 RFC 8032 인코딩을 그대로 푼다.
 */
public final class DiscordSignatureVerifier {

    private DiscordSignatureVerifier() {
    }

    public static boolean verify(String publicKeyHex, String timestamp, byte[] body, String signatureHex) {
        try {
            PublicKey key = decodeRawPublicKey(HexFormat.of().parseHex(publicKeyHex));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(timestamp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sig.update(body);
            return sig.verify(HexFormat.of().parseHex(signatureHex));
        } catch (Exception e) {
            return false; // 파싱 불가·형식 오류 전부 "서명 불일치"로 수렴 — 오류 메시지로 내부를 흘리지 않는다
        }
    }

    /** RFC 8032 raw 32바이트 → Java EdEC 공개키. 최상위 비트 = x 홀짝, 나머지 = 리틀엔디언 y. */
    static PublicKey decodeRawPublicKey(byte[] raw) throws Exception {
        if (raw.length != 32) {
            throw new IllegalArgumentException("Ed25519 공개키는 32바이트여야 한다");
        }
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[i] = raw[31 - i];   // 리틀엔디언 → 빅엔디언
        }
        boolean xOdd = (be[0] & 0x80) != 0;
        be[0] &= 0x7F;
        BigInteger y = new BigInteger(1, be);
        return KeyFactory.getInstance("Ed25519").generatePublic(
                new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
    }
}
