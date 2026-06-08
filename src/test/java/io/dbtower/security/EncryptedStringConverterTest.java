package io.dbtower.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 접두사 디스패치 계약을 고정한다 — "enc:v1:"이면 복호화, 아니면 평문 통과.
 * 이 계약이 지켜져야 암호화 도입 전의 평문 행이 마이그레이션 없이 계속 읽힌다.
 */
class EncryptedStringConverterTest {

    private final SecretCipher cipher = new SecretCipher(SecretCipherTest.TEST_KEY);
    private final EncryptedStringConverter converter = new EncryptedStringConverter(cipher);

    @Test
    void 저장_시_접두사가_붙은_암호문이_된다() {
        String column = converter.convertToDatabaseColumn("db-password");
        assertThat(column).startsWith("enc:v1:");
        assertThat(column).doesNotContain("db-password");
    }

    @Test
    void 접두사가_붙은_값은_복호화되어_돌아온다() {
        String column = converter.convertToDatabaseColumn("db-password");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo("db-password");
    }

    @Test
    void 접두사가_없는_기존_평문_행은_그대로_통과한다() {
        // 암호화 도입 전에 저장된 행 — 하위 호환의 핵심
        assertThat(converter.convertToEntityAttribute("legacy-plain")).isEqualTo("legacy-plain");
    }

    @Test
    void null은_양방향_모두_null이다() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void 키_미설정이면_평문_그대로_저장한다() {
        EncryptedStringConverter noKey = new EncryptedStringConverter(new SecretCipher(""));
        assertThat(noKey.convertToDatabaseColumn("plain-pass")).isEqualTo("plain-pass");
    }

    @Test
    void 키_미설정_상태에서_암호문을_만나면_평문인_척하지_않고_실패한다() {
        EncryptedStringConverter noKey = new EncryptedStringConverter(new SecretCipher(""));
        String encrypted = converter.convertToDatabaseColumn("secret");
        assertThatThrownBy(() -> noKey.convertToEntityAttribute(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }
}
