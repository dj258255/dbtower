package io.dbtower.operator.internal;

import io.dbtower.operator.model.IndexAdvice;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 인덱스 어드바이저 (B3) — 3-값 상태 규약, 후보 컬럼 인젝션 방어, 타 기종 UNSUPPORTED 정직성 검증.
 * 커넥션을 여는 실제 시뮬레이션(HypoPG)은 라이브 검증으로 확인하고, 여기서는 커넥션 없이 판정되는
 * 경계(형식 검증·미지원 분기)를 못 박는다.
 */
class IndexAdviceTest {

    @Test
    void 세_값_팩토리는_상태와_필드_규약을_지킨다() {
        IndexAdvice a = IndexAdvice.advised("좋아짐", "CREATE INDEX ON t (c)", "[before]", "[after]", 100.0, 10.0);
        assertEquals(IndexAdvice.ADVISED, a.status());
        assertEquals(100.0, a.beforeCost());
        assertEquals(10.0, a.afterCost());

        IndexAdvice n = IndexAdvice.noBenefit("그대로", "CREATE INDEX ON t (c)", "[before]", "[after]", 100.0, 100.0);
        assertEquals(IndexAdvice.NO_BENEFIT, n.status());

        IndexAdvice u = IndexAdvice.unsupported("범위 밖");
        assertEquals(IndexAdvice.UNSUPPORTED, u.status());
        assertNull(u.beforeCost(), "시뮬레이션을 못 했으면 비용은 null이어야 한다");
        assertNull(u.suggestedIndex());
    }

    @Test
    void 후보_컬럼은_식별자만_통과시켜_인젝션을_막는다() {
        assertEquals("CREATE INDEX ON users (category)",
                PostgresOperator.buildCreateIndexDdl("users(category)"));
        assertEquals("CREATE INDEX ON users (last_name, first_name)",
                PostgresOperator.buildCreateIndexDdl(" users ( last_name , first_name ) "));

        // 세미콜론·따옴표·괄호 등 인젝션 시도는 형식 위반으로 거부된다
        assertThrows(IllegalArgumentException.class,
                () -> PostgresOperator.buildCreateIndexDdl("users(category); DROP TABLE users"));
        assertThrows(IllegalArgumentException.class,
                () -> PostgresOperator.buildCreateIndexDdl("users(category)') ; SELECT ('"));
        assertThrows(IllegalArgumentException.class,
                () -> PostgresOperator.buildCreateIndexDdl("users"));
    }

    @Test
    void 후보_컬럼이_비면_커넥션_없이_UNSUPPORTED로_정직하게_보고한다() {
        // columns가 blank면 커넥션을 열기 전에 UNSUPPORTED를 돌려주므로 pools 없이 검증 가능
        DatabaseInstance pg = new DatabaseInstance(
                "pg", DbmsType.POSTGRESQL, "127.0.0.1", 5432, "sample", "postgres", "pw");
        IndexAdvice advice = new PostgresOperator(pg, null, null).adviseIndex("SELECT 1", "  ");
        assertEquals(IndexAdvice.UNSUPPORTED, advice.status());
        assertTrue(advice.detail().contains("범위 밖") || advice.detail().contains("미지정"), advice.detail());
    }

    @Test
    void 타_기종은_실제_인덱스가_필요하므로_UNSUPPORTED로_정직하게_보고한다() {
        // MySQL은 AbstractJdbcOperator 기본 UNSUPPORTED — 커넥션을 열지 않고 즉시 반환
        DatabaseInstance mysql = new DatabaseInstance(
                "my", DbmsType.MYSQL, "127.0.0.1", 3306, "app", "root", "pw");
        IndexAdvice advice = new MySqlOperator(mysql, null, null, null).adviseIndex("SELECT 1", "t(c)");
        assertEquals(IndexAdvice.UNSUPPORTED, advice.status());
        assertTrue(advice.detail().contains("PostgreSQL"), advice.detail());
    }
}
