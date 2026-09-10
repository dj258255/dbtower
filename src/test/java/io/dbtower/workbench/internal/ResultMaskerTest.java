package io.dbtower.workbench.internal;

import io.dbtower.operator.model.ResultColumn;
import io.dbtower.workbench.internal.ResultMasker.Masked;
import io.dbtower.workbench.internal.ResultMasker.Policy;
import io.dbtower.workbench.internal.domain.MaskingStrategy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultMaskerTest {

    private static final List<Policy> DEFAULTS = List.of(
            new Policy("*password*", MaskingStrategy.FULL),
            new Policy("*email*", MaskingStrategy.PARTIAL),
            new Policy("*phone*", MaskingStrategy.PARTIAL));

    private static ResultColumn col(String name) {
        return new ResultColumn(name, name, "VARCHAR");
    }

    @Test
    void 규칙에_걸린_열만_가리고_나머지는_그대로_둔다() {
        List<ResultColumn> columns = List.of(col("id"), col("email"), col("password_hash"), col("grade"));
        List<List<Object>> rows = List.of(Arrays.asList(1, "hong@example.com", "$2a$10$abc", "VIP"));

        Masked m = ResultMasker.apply(columns, rows, DEFAULTS);

        assertEquals(List.of("email", "password_hash"), m.maskedColumns());
        List<Object> row = m.rows().get(0);
        assertEquals(1, row.get(0));
        assertEquals("ho************om", row.get(1));
        assertEquals("****", row.get(2));
        assertEquals("VIP", row.get(3));
        assertEquals("hong@example.com", rows.get(0).get(1), "원본 행은 바꾸지 않는다");
    }

    @Test
    void 별칭으로_바꿔도_원래_컬럼명으로_잡는다() {
        // SELECT email AS e — 드라이버가 원래 이름을 알려주는 경우
        List<ResultColumn> columns = List.of(new ResultColumn("e", "email", "VARCHAR"));
        Masked m = ResultMasker.apply(columns, List.of(Arrays.asList((Object) "kim@corp.kr")), DEFAULTS);
        assertEquals(List.of("e"), m.maskedColumns());
        assertNotEquals("kim@corp.kr", m.rows().get(0).get(0));
    }

    @Test
    void 드라이버가_별칭만_줘도_문장의_별칭_쌍으로_잡는다() {
        // pgjdbc 실측: SELECT email AS e 의 원래 컬럼명 자리에도 "e"가 온다
        List<ResultColumn> columns = List.of(new ResultColumn("e", "e", "varchar"), new ResultColumn("p", "p", "varchar"),
                new ResultColumn("id", "id", "int4"));
        List<List<Object>> rows = List.of(Arrays.asList("hong@example.com", "01012345678", 1));
        for (String sql : List.of("SELECT email AS e, phone AS p, id FROM customers",
                "SELECT c.email e, c.phone \"p\", c.id FROM customers c",
                "SELECT e, p, id FROM (SELECT email AS e, phone AS p, id FROM customers) t")) {
            Masked m = ResultMasker.apply(columns, rows, DEFAULTS, sql);
            assertEquals(List.of("e", "p"), m.maskedColumns(), sql);
        }
    }

    @Test
    void 표현식으로_감싸면_못_잡는다는_한계를_고정한다() {
        // 이름 기반 계층의 한계를 테스트로 박아 둔다 — 이게 통과하면(=가려지면) 문서의 한계 서술을 고쳐야 한다
        Masked m = ResultMasker.apply(List.of(new ResultColumn("x", "x", "varchar")),
                List.of(Arrays.asList((Object) "hong@example.com")), DEFAULTS, "SELECT CONCAT(email, '') AS x FROM customers");
        assertTrue(m.maskedColumns().isEmpty());
    }

    @Test
    void null은_null로_남기고_한글은_코드포인트_단위로_자른다() {
        assertNull(ResultMasker.mask(null, MaskingStrategy.FULL));
        assertEquals("홍**", ResultMasker.partial("홍길동"));
        assertEquals("*", ResultMasker.partial("a"));
        assertEquals("01*******78", ResultMasker.partial("01012345678"));
    }

    @Test
    void 해시는_같은_값이면_같은_토큰이다() {
        Object a = ResultMasker.mask("hong@example.com", MaskingStrategy.HASH);
        Object b = ResultMasker.mask("hong@example.com", MaskingStrategy.HASH);
        Object c = ResultMasker.mask("kim@example.com", MaskingStrategy.HASH);
        assertEquals(a, b, "원문 없이도 같은 고객끼리 묶을 수 있어야 한다");
        assertNotEquals(a, c);
        assertTrue(a.toString().startsWith("h:"));
    }

    @Test
    void 먼저_온_규칙이_이긴다() {
        // 인스턴스 규칙(HASH)이 공통 규칙(PARTIAL)보다 앞에 오면 인스턴스 규칙이 적용된다
        List<Policy> ordered = List.of(new Policy("email", MaskingStrategy.HASH), new Policy("*email*", MaskingStrategy.PARTIAL));
        Masked m = ResultMasker.apply(List.of(col("email")), List.of(Arrays.asList((Object) "x@y.z")), ordered);
        assertTrue(m.rows().get(0).get(0).toString().startsWith("h:"));
    }

    @Test
    void 규칙이_없으면_같은_행_목록을_돌려준다() {
        List<List<Object>> rows = List.of(Arrays.asList((Object) "a"));
        Masked m = ResultMasker.apply(List.of(col("name")), rows, DEFAULTS);
        assertSame(rows, m.rows());
        assertTrue(m.maskedColumns().isEmpty());
    }
}
