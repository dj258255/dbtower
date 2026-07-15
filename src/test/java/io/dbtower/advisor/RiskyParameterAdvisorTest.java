package io.dbtower.advisor;

import io.dbtower.advisor.internal.RiskyParameterAdvisor;

import io.dbtower.operator.model.DbParameter;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 위험 파라미터값 규칙 — 기종별 위반/정상 케이스를 순수 판정으로 고정한다. */
class RiskyParameterAdvisorTest {

    private final RiskyParameterAdvisor advisor = new RiskyParameterAdvisor();

    private DbParameter p(String name, String value) {
        return new DbParameter(name, value, null);
    }

    @Test
    void 지원_기종은_MySQL_PostgreSQL_MSSQL뿐이고_Oracle_Mongo는_미지원() {
        assertTrue(advisor.supports(DbmsType.MYSQL));
        assertTrue(advisor.supports(DbmsType.POSTGRESQL));
        assertTrue(advisor.supports(DbmsType.MSSQL));
        assertFalse(advisor.supports(DbmsType.ORACLE));
        assertFalse(advisor.supports(DbmsType.MONGODB));
    }

    @Test
    void PostgreSQL_fsync_off는_치명으로_잡는다() {
        List<AdvisorFinding> f = advisor.evaluate(DbmsType.POSTGRESQL, List.of(
                p("fsync", "off"), p("max_connections", "100")));
        assertEquals(1, f.size());
        assertEquals(Severity.CRITICAL, f.get(0).severity());
        assertTrue(f.get(0).title().contains("fsync"));
    }

    @Test
    void PostgreSQL_정상_설정은_지적이_없다() {
        List<AdvisorFinding> f = advisor.evaluate(DbmsType.POSTGRESQL, List.of(
                p("fsync", "on"), p("full_page_writes", "on"),
                p("synchronous_commit", "on"), p("max_connections", "100")));
        assertTrue(f.isEmpty());
    }

    @Test
    void MySQL_STRICT_없는_sql_mode와_내구성_완화를_잡는다() {
        List<AdvisorFinding> f = advisor.evaluate(DbmsType.MYSQL, List.of(
                p("sql_mode", "ONLY_FULL_GROUP_BY,NO_ENGINE_SUBSTITUTION"),
                p("innodb_flush_log_at_trx_commit", "2"),
                p("max_connections", "500")));
        assertEquals(2, f.size());
        assertTrue(f.stream().anyMatch(x -> x.title().contains("STRICT")));
        assertTrue(f.stream().anyMatch(x -> x.title().contains("flush_log")));
    }

    @Test
    void MySQL_STRICT_포함_정상값은_통과() {
        List<AdvisorFinding> f = advisor.evaluate(DbmsType.MYSQL, List.of(
                p("sql_mode", "STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION"),
                p("innodb_flush_log_at_trx_commit", "1"),
                p("max_connections", "500")));
        assertTrue(f.isEmpty());
    }

    @Test
    void MySQL_기본_max_connections는_정보로_알린다() {
        List<AdvisorFinding> f = advisor.evaluate(DbmsType.MYSQL, List.of(
                p("sql_mode", "STRICT_TRANS_TABLES"),
                p("innodb_flush_log_at_trx_commit", "1"),
                p("max_connections", "151")));
        assertEquals(1, f.size());
        assertEquals(Severity.INFO, f.get(0).severity());
    }

    @Test
    void MSSQL_무제한_메모리와_MAXDOP_0을_잡는다() {
        List<AdvisorFinding> f = advisor.evaluate(DbmsType.MSSQL, List.of(
                p("max server memory (MB)", "2147483647"),
                p("max degree of parallelism", "0")));
        assertEquals(2, f.size());
    }

    @Test
    void 값이_숫자가_아니면_조용히_건너뛴다() {
        List<AdvisorFinding> f = advisor.evaluate(DbmsType.MYSQL, List.of(
                p("sql_mode", "STRICT_TRANS_TABLES"),
                p("innodb_flush_log_at_trx_commit", "1"),
                p("max_connections", "unknown")));
        assertTrue(f.isEmpty());
    }
}
