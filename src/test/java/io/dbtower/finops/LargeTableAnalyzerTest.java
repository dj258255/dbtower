package io.dbtower.finops;

import io.dbtower.finops.internal.LargeTableAnalyzer;
import io.dbtower.finops.internal.WasteCandidate;
import io.dbtower.finops.internal.WasteKind;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.TableStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 큰 테이블·과다 인덱싱 신호 — 크기 임계·인덱스>데이터 판정. */
class LargeTableAnalyzerTest {

    private final LargeTableAnalyzer analyzer = new LargeTableAnalyzer();
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);
    private final DatabaseInstance instance =
            new DatabaseInstance("db1", DbmsType.MYSQL, "h", 3306, "sample", "u", "p");

    private static final long MB = 1024L * 1024;

    @Test
    void 큰_테이블과_과다_인덱싱을_각각_신호로_낸다() {
        when(operator.tableStats(Mockito.anyInt())).thenReturn(List.of(
                new TableStat("big", 1_000_000, 300 * MB, 10 * MB),   // 총 310MB → LARGE_TABLE
                new TableStat("overidx", 100, 5 * MB, 20 * MB),        // 인덱스>데이터, 인덱스 20MB → OVER_INDEXED
                new TableStat("small", 10, 1024, 512)));               // 임계 미만 → 없음

        List<WasteCandidate> c = analyzer.analyze(instance, operator);

        assertTrue(c.stream().anyMatch(w -> w.kind() == WasteKind.LARGE_TABLE && w.target().equals("big")));
        assertTrue(c.stream().anyMatch(w -> w.kind() == WasteKind.OVER_INDEXED && w.target().equals("overidx")));
        assertFalse(c.stream().anyMatch(w -> w.target().equals("small")));
    }

    @Test
    void 작은_테이블만_있으면_후보가_없다() {
        when(operator.tableStats(Mockito.anyInt())).thenReturn(List.of(
                new TableStat("t", 5, 2048, 1024)));
        assertTrue(analyzer.analyze(instance, operator).isEmpty());
    }
}
