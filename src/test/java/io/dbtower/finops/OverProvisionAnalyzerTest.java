package io.dbtower.finops;

import io.dbtower.operator.DbParameter;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.SessionInfo;
import io.dbtower.operator.TableStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 오버프로비저닝 신호 — 연결 여유·메모리 여유, 단위 환산, 지원 기종 제한. */
class OverProvisionAnalyzerTest {

    private final OverProvisionAnalyzer analyzer = new OverProvisionAnalyzer();
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);

    private DatabaseInstance instance(DbmsType type) {
        return new DatabaseInstance("db1", type, "h", 5432, "sample", "u", "p");
    }

    private List<SessionInfo> sessions(int n) {
        List<SessionInfo> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new SessionInfo(i, "u", "active", null, null, "q", 1.0));
        }
        return list;
    }

    @Test
    void PostgreSQL_MySQL만_지원한다() {
        assertTrue(analyzer.supports(DbmsType.POSTGRESQL));
        assertTrue(analyzer.supports(DbmsType.MYSQL));
        assertFalse(analyzer.supports(DbmsType.ORACLE));
        assertFalse(analyzer.supports(DbmsType.MSSQL));
        assertFalse(analyzer.supports(DbmsType.MONGODB));
    }

    @Test
    void 활성세션이_max_connections를_크게_밑돌면_연결여유_신호() {
        when(operator.parameters()).thenReturn(List.of(
                new DbParameter("max_connections", "200", null)));
        when(operator.activeSessions(Mockito.anyInt())).thenReturn(sessions(5)); // 5 ≤ 20(10%)
        when(operator.tableStats(Mockito.anyInt())).thenReturn(List.of());

        List<WasteCandidate> c = analyzer.analyze(instance(DbmsType.MYSQL), operator);

        assertTrue(c.stream().anyMatch(w -> w.kind() == WasteKind.CONNECTION_HEADROOM));
    }

    @Test
    void 활성세션이_충분히_많으면_연결여유_신호_없음() {
        when(operator.parameters()).thenReturn(List.of(
                new DbParameter("max_connections", "200", null)));
        when(operator.activeSessions(Mockito.anyInt())).thenReturn(sessions(50)); // 50 > 20
        when(operator.tableStats(Mockito.anyInt())).thenReturn(List.of());

        List<WasteCandidate> c = analyzer.analyze(instance(DbmsType.MYSQL), operator);

        assertFalse(c.stream().anyMatch(w -> w.kind() == WasteKind.CONNECTION_HEADROOM));
    }

    @Test
    void PG_shared_buffers_단위_환산으로_메모리여유_신호() {
        // shared_buffers = 131072 블록 × 8kB = 1GB, 데이터 총량 100MB → 약 10배
        when(operator.parameters()).thenReturn(List.of(
                new DbParameter("shared_buffers", "131072", "8kB")));
        when(operator.activeSessions(Mockito.anyInt())).thenReturn(sessions(0));
        when(operator.tableStats(Mockito.anyInt())).thenReturn(List.of(
                new TableStat("t", 100, 90L * 1024 * 1024, 10L * 1024 * 1024))); // 100MB

        List<WasteCandidate> c = analyzer.analyze(instance(DbmsType.POSTGRESQL), operator);

        assertTrue(c.stream().anyMatch(w -> w.kind() == WasteKind.MEMORY_HEADROOM));
    }

    @Test
    void 버퍼가_데이터보다_크게_넉넉하지_않으면_메모리여유_신호_없음() {
        // innodb_buffer_pool_size = 128MB(단위 없는 바이트), 데이터 100MB → 1.28배(임계 4배 미만)
        when(operator.parameters()).thenReturn(List.of(
                new DbParameter("innodb_buffer_pool_size", String.valueOf(128L * 1024 * 1024), null)));
        when(operator.activeSessions(Mockito.anyInt())).thenReturn(sessions(0));
        when(operator.tableStats(Mockito.anyInt())).thenReturn(List.of(
                new TableStat("t", 100, 100L * 1024 * 1024, 0)));

        List<WasteCandidate> c = analyzer.analyze(instance(DbmsType.MYSQL), operator);

        assertFalse(c.stream().anyMatch(w -> w.kind() == WasteKind.MEMORY_HEADROOM));
    }
}
