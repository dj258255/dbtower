package io.dbtower.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-3 / Phase 0: 암호화 fail-closed 경계를 못 박는다. 배포 프로필(prod·docker)에서 키가 없으면
 * 조용한 평문 저장 대신 기동을 거부해야 하고, dev/test/기본 프로필은 하위호환·테스트 부팅 편의를 위해
 * WARN+평문 폴백을 그대로 유지해야 한다. (셀프호스트는 docker 프로필이라 여기가 진짜 방어선)
 */
class SecretCipherProfileTest {

    @Test
    void prod_프로필에_키가_없으면_기동을_거부한다() {
        assertThatThrownBy(() -> new SecretCipher("", "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }

    @Test
    void docker_프로필에_키가_없으면_기동을_거부한다() {
        // 셀프호스트 경로 — 예전엔 prod만 막아 이 경로가 평문으로 뚫려 있었다(CWE-312)
        assertThatThrownBy(() -> new SecretCipher("", "docker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docker");
    }

    @Test
    void prod가_콤마_목록에_섞여_있어도_거부한다() {
        assertThatThrownBy(() -> new SecretCipher("", "staging,prod"))
                .isInstanceOf(IllegalStateException.class);
        // 대소문자 무시
        assertThatThrownBy(() -> new SecretCipher("", "PROD"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dev_프로필은_키가_없어도_평문_폴백을_유지한다() {
        SecretCipher dev = new SecretCipher("", "dev");
        assertThat(dev.enabled()).isFalse();
    }

    @Test
    void 프로필_미설정_기본은_키가_없어도_평문_폴백을_유지한다() {
        SecretCipher fallback = new SecretCipher("");
        assertThat(fallback.enabled()).isFalse();
    }

    @Test
    void prod라도_키가_있으면_정상_기동하고_암호화가_활성이다() {
        assertThatCode(() -> {
            SecretCipher prod = new SecretCipher(SecretCipherTest.TEST_KEY, "prod");
            assertThat(prod.enabled()).isTrue();
        }).doesNotThrowAnyException();
    }
}
