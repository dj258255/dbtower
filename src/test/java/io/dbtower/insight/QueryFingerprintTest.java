package io.dbtower.insight;

import io.dbtower.insight.internal.QueryFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지문은 "같은 모양의 쿼리를 같은 키로 묶는" 것이 전부다. 완전한 SQL 파싱은 이 자리에 과하고,
 * 파서를 태우면 샘플러가 무거워져 A9 원칙과 충돌한다. 그래서 얕은 정규화만 하고, 그 결과
 * 무엇이 묶이고 무엇이 안 묶이는지를 여기서 못박는다.
 */
class QueryFingerprintTest {

    @Test
    @DisplayName("파라미터만 다른 쿼리는 같은 지문으로 묶인다 — 이게 지문의 존재 이유다")
    void sameShapeDifferentLiterals() {
        String a = QueryFingerprint.of("SELECT * FROM users WHERE id = 42");
        String b = QueryFingerprint.of("SELECT * FROM users WHERE id = 9999");
        assertThat(a).isNotNull().isEqualTo(b);
    }

    @Test
    @DisplayName("문자열 리터럴도 파라미터로 접힌다 — 개인정보가 지문에 남지 않는 근거")
    void stringLiteralsCollapse() {
        String a = QueryFingerprint.of("SELECT * FROM users WHERE email = 'a@x.com'");
        String b = QueryFingerprint.of("SELECT * FROM users WHERE email = 'b@y.com'");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("공백과 대소문자 차이는 무시한다")
    void whitespaceAndCaseInsensitive() {
        String a = QueryFingerprint.of("select  *\n  from users");
        String b = QueryFingerprint.of("SELECT * FROM users");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("모양이 다르면 지문도 다르다")
    void differentShapeDiffers() {
        String a = QueryFingerprint.of("SELECT * FROM users WHERE id = 1");
        String b = QueryFingerprint.of("SELECT * FROM orders WHERE id = 1");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("null과 공백은 지문 없음 — 없는 걸 지어내지 않는다")
    void nullSafe() {
        assertThat(QueryFingerprint.of(null)).isNull();
        assertThat(QueryFingerprint.of("   ")).isNull();
    }

    @Test
    @DisplayName("지문 길이는 32헥스로 고정 — 컬럼 VARCHAR(32)와 맞는다")
    void fixedLength() {
        assertThat(QueryFingerprint.of("SELECT 1")).hasSize(32);
    }
}
