package io.dbtower.advisor;

import io.dbtower.operator.QueryStat;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 모니터링 계정 조용한 저하 규칙 — PostgreSQL 마스킹 토큰을 잡고, 다른 기종은 미지원. */
class MonitoringPrivilegeAdvisorTest {

    private final MonitoringPrivilegeAdvisor advisor = new MonitoringPrivilegeAdvisor();

    private QueryStat stat(String queryText) {
        return new QueryStat("id", queryText, 100, 50.0, 100);
    }

    @Test
    void PostgreSQL만_지원한다() {
        assertTrue(advisor.supports(DbmsType.POSTGRESQL));
        assertFalse(advisor.supports(DbmsType.MYSQL));
        assertFalse(advisor.supports(DbmsType.MONGODB));
    }

    @Test
    void 마스킹된_통계가_있으면_치명으로_잡고_계정명을_권고에_넣는다() {
        List<AdvisorFinding> f = advisor.evaluate("dbtower_monitor", List.of(
                stat("<insufficient privilege>"), stat("<insufficient privilege>")));
        assertEquals(1, f.size());
        assertEquals(Severity.CRITICAL, f.get(0).severity());
        assertTrue(f.get(0).recommendation().contains("dbtower_monitor"));
        assertTrue(f.get(0).detail().contains("2"));
    }

    @Test
    void 정상_통계는_통과() {
        List<AdvisorFinding> f = advisor.evaluate("dbtower_monitor", List.of(
                stat("SELECT pg_database_size($1)"), stat("SELECT * FROM orders")));
        assertTrue(f.isEmpty());
    }
}
