package io.dbtower.finops;

import io.dbtower.advisor.DuplicateIndexAdvisor;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.IndexSchema;
import io.dbtower.operator.SchemaSnapshot;
import io.dbtower.operator.TableSchema;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 중복·잉여 인덱스 — D2 DuplicateIndexAdvisor를 재사용해 낭비 후보로 변환하는지. */
class RedundantIndexAnalyzerTest {

    private final RedundantIndexAnalyzer analyzer = new RedundantIndexAnalyzer(new DuplicateIndexAdvisor());
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);
    private final DatabaseInstance instance =
            new DatabaseInstance("db1", DbmsType.MYSQL, "h", 3306, "sample", "u", "p");

    @Test
    void 모든_기종을_지원한다() {
        for (DbmsType t : DbmsType.values()) {
            assertTrue(analyzer.supports(t));
        }
    }

    @Test
    void D2_판정을_그대로_중복인덱스_낭비_후보로_옮긴다() {
        when(operator.describeSchema()).thenReturn(new SchemaSnapshot("MYSQL", "sample", List.of(
                new TableSchema("orders", List.of(), List.of(
                        new IndexSchema("idx_a", List.of("user_id", "created_at"), false),
                        new IndexSchema("idx_b", List.of("user_id", "created_at"), false)))),
                false, 200));

        List<WasteCandidate> c = analyzer.analyze(instance, operator);

        assertEquals(1, c.size());
        assertEquals(WasteKind.REDUNDANT_INDEX, c.get(0).kind());
        assertTrue(c.get(0).target().contains("완전 중복"));
    }

    @Test
    void 겹치는_인덱스가_없으면_후보가_없다() {
        when(operator.describeSchema()).thenReturn(new SchemaSnapshot("MYSQL", "sample", List.of(
                new TableSchema("orders", List.of(), List.of(
                        new IndexSchema("idx_user", List.of("user_id"), false),
                        new IndexSchema("idx_status", List.of("status"), false)))),
                false, 200));
        assertTrue(analyzer.analyze(instance, operator).isEmpty());
    }
}
