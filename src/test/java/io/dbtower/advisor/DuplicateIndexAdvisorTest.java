package io.dbtower.advisor;

import io.dbtower.operator.model.IndexSchema;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableSchema;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 중복·접두 잉여 인덱스 후보 규칙 — 구조만으로 판정한다. */
class DuplicateIndexAdvisorTest {

    private final DuplicateIndexAdvisor advisor = new DuplicateIndexAdvisor();

    private IndexSchema idx(String name, boolean unique, String... cols) {
        return new IndexSchema(name, List.of(cols), unique);
    }

    private SchemaSnapshot snapshot(TableSchema... tables) {
        return new SchemaSnapshot("MYSQL", "sample", List.of(tables), false, 200);
    }

    @Test
    void 모든_기종을_지원한다() {
        for (DbmsType t : DbmsType.values()) {
            assertTrue(advisor.supports(t));
        }
    }

    @Test
    void 컬럼_구성이_같은_인덱스는_완전_중복으로_잡는다() {
        SchemaSnapshot s = snapshot(new TableSchema("orders", List.of(),
                List.of(idx("idx_a", false, "user_id", "created_at"),
                        idx("idx_b", false, "user_id", "created_at"))));
        List<AdvisorFinding> f = advisor.evaluate(s);
        assertEquals(1, f.size());
        assertEquals(Severity.WARNING, f.get(0).severity());
        assertTrue(f.get(0).title().contains("완전 중복"));
    }

    @Test
    void 한_인덱스가_다른_인덱스의_접두이면_잉여_후보로_잡는다() {
        SchemaSnapshot s = snapshot(new TableSchema("orders", List.of(),
                List.of(idx("idx_user", false, "user_id"),
                        idx("idx_user_time", false, "user_id", "created_at"))));
        List<AdvisorFinding> f = advisor.evaluate(s);
        assertEquals(1, f.size());
        assertEquals(Severity.INFO, f.get(0).severity());
        assertTrue(f.get(0).title().contains("접두 잉여"));
    }

    @Test
    void 서로_다른_인덱스는_지적하지_않는다() {
        SchemaSnapshot s = snapshot(new TableSchema("orders", List.of(),
                List.of(idx("idx_user", false, "user_id"),
                        idx("idx_status", false, "status"))));
        assertTrue(advisor.evaluate(s).isEmpty());
    }

    @Test
    void 컬럼이_비어있는_인덱스는_비교에서_제외한다() {
        SchemaSnapshot s = snapshot(new TableSchema("orders", List.of(),
                List.of(idx("expr_a", false), idx("expr_b", false))));
        assertTrue(advisor.evaluate(s).isEmpty());
    }
}
