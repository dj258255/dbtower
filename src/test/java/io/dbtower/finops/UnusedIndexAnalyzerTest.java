package io.dbtower.finops;

import io.dbtower.advisor.Severity;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.IndexUsage;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 미사용 인덱스 판정 — 스캔 0 & 비유니크만 후보, 유니크·사용됨·UNSUPPORTED 행은 제외. */
class UnusedIndexAnalyzerTest {

    private final UnusedIndexAnalyzer analyzer = new UnusedIndexAnalyzer();
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);
    private final DatabaseInstance instance =
            new DatabaseInstance("db1", DbmsType.POSTGRESQL, "h", 5432, "sample", "u", "p");

    @Test
    void Oracle만_미지원이고_나머지_기종은_지원한다() {
        assertFalse(analyzer.supports(DbmsType.ORACLE));
        assertTrue(analyzer.supports(DbmsType.POSTGRESQL));
        assertTrue(analyzer.supports(DbmsType.MYSQL));
        assertTrue(analyzer.supports(DbmsType.MSSQL));
        assertTrue(analyzer.supports(DbmsType.MONGODB));
    }

    @Test
    void 스캔0_비유니크만_미사용_후보로_잡고_나머지는_제외한다() {
        when(operator.indexUsage(Mockito.anyInt())).thenReturn(List.of(
                new IndexUsage("orders", "idx_unused", 0L, 4096L, false, IndexUsage.NATIVE),   // 후보
                new IndexUsage("orders", "orders_pkey", 0L, 8192L, true, IndexUsage.NATIVE),    // 유니크 → 제외
                new IndexUsage("orders", "idx_hot", 5000L, 4096L, false, IndexUsage.NATIVE),    // 사용됨 → 제외
                IndexUsage.unsupported("사유")));                                               // UNSUPPORTED 행 → 제외

        List<WasteCandidate> c = analyzer.analyze(instance, operator);

        assertEquals(1, c.size());
        WasteCandidate w = c.get(0);
        assertEquals(WasteKind.UNUSED_INDEX, w.kind());
        assertEquals(Severity.WARNING, w.severity());
        assertEquals("orders.idx_unused", w.target());
        assertTrue(w.evidence().contains("0회"));
    }

    @Test
    void 미사용이_없으면_빈_목록이다() {
        when(operator.indexUsage(Mockito.anyInt())).thenReturn(List.of(
                new IndexUsage("orders", "idx_hot", 3L, 4096L, false, IndexUsage.NATIVE)));
        assertTrue(analyzer.analyze(instance, operator).isEmpty());
    }
}
