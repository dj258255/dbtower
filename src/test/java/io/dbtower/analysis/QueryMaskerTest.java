package io.dbtower.analysis;

import org.junit.jupiter.api.Test;

import static io.dbtower.analysis.QueryMasker.maskLiterals;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리터럴 전용 마스킹 — 값(민감정보 서식지)은 가리고 구조(식별자·키워드·플레이스홀더)는 보존한다.
 * 진단력 유지가 목적이므로 과도 마스킹(구조 훼손)이 곧 버그다.
 */
class QueryMaskerTest {

    @Test
    void 문자열과_숫자_리터럴만_가린다() {
        assertThat(maskLiterals("SELECT * FROM users WHERE email = 'hong@x.com' AND age > 30 LIMIT 50"))
                .isEqualTo("SELECT * FROM users WHERE email = ? AND age > ? LIMIT ?");
    }

    @Test
    void 식별자_꼬리_숫자와_따옴표_식별자는_보존한다() {
        assertThat(maskLiterals("SELECT col1, `col2`, \"Col3\" FROM user_2024 WHERE k = 7"))
                .isEqualTo("SELECT col1, `col2`, \"Col3\" FROM user_2024 WHERE k = ?");
        // SQL Server 대괄호 식별자도 보존
        assertThat(maskLiterals("SELECT [Order Id] FROM [dbo].[orders] WHERE qty = 3"))
                .isEqualTo("SELECT [Order Id] FROM [dbo].[orders] WHERE qty = ?");
    }

    @Test
    void 플레이스홀더는_그대로_둔다_멱등성() {
        // 이미 정규화된 텍스트(MySQL digest ? / PG $1)에 다시 걸어도 변하지 않아야 한다
        String mysqlDigest = "SELECT `u` . `name` FROM `users` WHERE `id` > ?";
        assertThat(maskLiterals(mysqlDigest)).isEqualTo(mysqlDigest);
        String pgNormalized = "SELECT name FROM users WHERE id = $1 AND status = $2";
        assertThat(maskLiterals(pgNormalized)).isEqualTo(pgNormalized);
    }

    @Test
    void 이스케이프된_따옴표를_넘어_문자열_전체를_가린다() {
        assertThat(maskLiterals("SELECT 1 FROM t WHERE note = 'it''s a test' AND x = 'a\\'b'"))
                .isEqualTo("SELECT ? FROM t WHERE note = ? AND x = ?");
    }

    @Test
    void 달러_인용과_16진_지수_리터럴도_가린다() {
        assertThat(maskLiterals("SELECT $tag$secret value$tag$ , $$another$$"))
                .isEqualTo("SELECT ? , ?");
        assertThat(maskLiterals("SELECT 0xDEADBEEF, 1.5e-3, .25 FROM t"))
                .isEqualTo("SELECT ?, ?, ? FROM t");
    }

    @Test
    void 주석은_원문_유지_인젝션형_문자열도_통째로_마스킹() {
        assertThat(maskLiterals("SELECT 1 -- comment 123\nFROM t /* block 456 */"))
                .isEqualTo("SELECT ? -- comment 123\nFROM t /* block 456 */");
        // 문자열 안의 SQL 조각(인젝션형)도 리터럴이므로 통째로 ? 하나가 된다
        assertThat(maskLiterals("SELECT * FROM t WHERE name = '1'' OR ''1''=''1'"))
                .isEqualTo("SELECT * FROM t WHERE name = ?");
    }

    @Test
    void 토글_동작_enabled_false면_원문_maskAiPrompt는_둘다_켜져야_가린다() {
        QueryMasker off = new QueryMasker(false, true);
        assertThat(off.apply("SELECT 'x'")).isEqualTo("SELECT 'x'");
        assertThat(off.applyForAiPrompt("SELECT 'x'")).isEqualTo("SELECT 'x'");

        QueryMasker onNoAi = new QueryMasker(true, false);
        assertThat(onNoAi.apply("SELECT 'x'")).isEqualTo("SELECT ?");
        assertThat(onNoAi.applyForAiPrompt("SELECT 'x'")).isEqualTo("SELECT 'x'");

        QueryMasker onAi = new QueryMasker(true, true);
        assertThat(onAi.applyForAiPrompt("SELECT 'x'")).isEqualTo("SELECT ?");
    }
}
