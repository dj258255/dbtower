package io.dbtower.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AES-256-GCM 암복호화의 성질을 고정한다 — 왕복 복원, IV 무작위성, 변조 검출.
 * 키 미설정(fail-open)과 잘못된 키(fail-fast)의 경계도 여기서 못 박는다.
 */
class SecretCipherTest {

    /** base64("0123456789abcdef0123456789abcdef") — 32바이트 고정 테스트 키 */
    static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final SecretCipher cipher = new SecretCipher(TEST_KEY);

    @Test
    void 암호화한_값은_복호화로_원문이_된다() {
        String encrypted = cipher.encrypt("s3cret-p@ssword");
        assertThat(encrypted).isNotEqualTo("s3cret-p@ssword");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("s3cret-p@ssword");
    }

    @Test
    void 한글_비밀번호도_왕복_복원된다() {
        String encrypted = cipher.encrypt("비밀번호123!");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("비밀번호123!");
    }

    @Test
    void 같은_평문도_IV가_달라_암호문이_매번_다르다() {
        String first = cipher.encrypt("same-plain");
        String second = cipher.encrypt("same-plain");
        assertThat(first).isNotEqualTo(second);
        // 그래도 둘 다 같은 원문으로 풀린다
        assertThat(cipher.decrypt(first)).isEqualTo("same-plain");
        assertThat(cipher.decrypt(second)).isEqualTo("same-plain");
    }

    @Test
    void 변조된_암호문은_복호화가_실패한다() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("integrity"));
        raw[raw.length - 1] ^= 0x01; // 마지막 바이트 1비트 뒤집기 — GCM 태그 검증이 잡아야 한다
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("복호화 실패");
    }

    @Test
    void 다른_키로_만든_암호문은_풀리지_않는다() {
        String otherKey = Base64.getEncoder()
                .encodeToString("fedcba9876543210fedcba9876543210".getBytes());
        String foreign = new SecretCipher(otherKey).encrypt("plain");

        assertThatThrownBy(() -> cipher.decrypt(foreign))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키_미설정이면_비활성으로_기동하고_암호화_시도는_예외다() {
        SecretCipher disabled = new SecretCipher("");
        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> disabled.encrypt("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> disabled.decrypt("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키가_32바이트가_아니면_기동을_거부한다() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThatThrownBy(() -> new SecretCipher(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void 키가_base64가_아니면_기동을_거부한다() {
        assertThatThrownBy(() -> new SecretCipher("!!not-base64!!"))
                .isInstanceOf(IllegalStateException.class);
    }
}
