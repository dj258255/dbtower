package io.dbtower.operator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 민감 파라미터 마스킹 검증 (B6) — 이름에 비밀번호·키류 조각이 들어가면 값을 노출하지 않는지 증명한다.
 * 마스킹 토큰이 좌우 동일하므로 drift에서 '변경'으로 튀지 않는다는 점도 함께 확인한다.
 */
class ParameterSupportTest {

    @Test
    void 민감_이름은_값을_마스킹한다() {
        DbParameter masked = ParameterSupport.of("master_password", "s3cr3t", null);
        assertEquals(ParameterSupport.MASKED, masked.value());
        assertNotEquals("s3cr3t", masked.value());

        // 대소문자·부분일치도 잡는다
        assertTrue(ParameterSupport.isSensitive("SSL_KEY_FILE"));
        assertTrue(ParameterSupport.isSensitive("some_secret_option"));
    }

    @Test
    void 일반_파라미터는_값을_그대로_둔다() {
        DbParameter p = ParameterSupport.of("max_connections", "100", null);
        assertEquals("100", p.value());
        assertFalse(ParameterSupport.isSensitive("max_connections"));

        // null 값은 빈 문자열로 정규화(diff 비교가 NPE 없이 결정적)
        assertEquals("", ParameterSupport.of("work_mem", null, null).value());
    }

    @Test
    void 마스킹은_좌우_동일해_drift로_튀지_않는다() {
        DbParameter left = ParameterSupport.of("admin_password", "aaa", null);
        DbParameter right = ParameterSupport.of("admin_password", "bbb", null);
        assertEquals(left.value(), right.value(), "실제 값이 달라도 마스킹 후엔 동일해야 한다");
    }
}
