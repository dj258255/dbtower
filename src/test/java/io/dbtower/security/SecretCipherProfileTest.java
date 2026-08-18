package io.dbtower.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-3 / Phase 0: 암호화 fail-closed 경계를 못 박는다.
 *
 * <p>다관점 감사에서 이 게이트가 <b>프로필 이름 화이트리스트</b>(prod·docker)에 걸려 있다는 것이
 * 드러났다. 이 저장소에는 앱 자체의 K8s 매니페스트가 없어 배포자가 프로필 이름을 직접 정하는데,
 * production·prd·k8s·internal 중 아무거나 쓰면 게이트를 통과하고 대상 DB 자격증명 전량이
 * 평문으로 저장됐다. 신호는 WARN 한 줄뿐이었다.
 *
 * <p>이제 규칙이 뒤집혔다: <b>dev/local/test가 아니면(미설정 포함) 전부 거부</b>.
 * 평문으로 띄우려면 allow-plaintext를 명시적으로 켜야 한다 — 의도가 설정에 기록되게.
 */
class SecretCipherProfileTest {

    /** 평문 폴백을 명시적으로 허용하지 않은, 실제 배포에 가까운 생성 경로. */
    private static SecretCipher deploy(String key, String profiles) {
        return new SecretCipher(key, profiles, false);
    }

    @Test
    void 배포_프로필에_키가_없으면_기동을_거부한다() {
        assertThatThrownBy(() -> deploy("", "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
        // 셀프호스트 경로
        assertThatThrownBy(() -> deploy("", "docker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docker");
    }

    @Test
    void 목록에_없는_배포_이름도_전부_거부한다() {
        // 회귀 방어: 예전에는 prod·docker만 막아서 아래가 전부 평문으로 뚫려 있었다
        for (String profile : new String[] {"production", "prd", "k8s", "internal", "stage", "live"}) {
            assertThatThrownBy(() -> deploy("", profile))
                    .as("프로필 %s는 개발용이 아니므로 거부해야 한다", profile)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 프로필_미설정도_거부한다() {
        // 배포 의도를 알 수 없으면 안전한 쪽에 선다 — java -jar 로 그냥 띄우는 경로가 여기다
        assertThatThrownBy(() -> deploy("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("미설정");
    }

    @Test
    void 개발용이_하나라도_아니면_거부한다() {
        assertThatThrownBy(() -> deploy("", "dev,prod"))
                .isInstanceOf(IllegalStateException.class);
        // 대소문자 무시
        assertThatThrownBy(() -> deploy("", "PROD"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 개발용_프로필은_키가_없어도_평문_폴백을_유지한다() {
        for (String profile : new String[] {"dev", "local", "test"}) {
            assertThat(deploy("", profile).enabled())
                    .as("프로필 %s는 로컬 개발 편의를 위해 평문 폴백", profile)
                    .isFalse();
        }
    }

    @Test
    void 명시적_opt_in이면_배포_프로필에서도_평문을_허용한다() {
        // 탈출구는 남기되 설정에 의도가 기록되게 — 조용한 폴백과 다르다
        SecretCipher explicit = new SecretCipher("", "production", true);
        assertThat(explicit.enabled()).isFalse();
    }

    @Test
    void 키가_있으면_어느_프로필이든_정상_기동하고_암호화가_활성이다() {
        assertThatCode(() -> {
            assertThat(deploy(SecretCipherTest.TEST_KEY, "prod").enabled()).isTrue();
            assertThat(deploy(SecretCipherTest.TEST_KEY, "").enabled()).isTrue();
        }).doesNotThrowAnyException();
    }
}
