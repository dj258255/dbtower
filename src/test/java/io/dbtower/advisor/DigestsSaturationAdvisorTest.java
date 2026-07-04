package io.dbtower.advisor;

import io.dbtower.operator.DbParameter;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** MySQL digest 포화·실명 규칙 — 기본 상한/OFF는 지적, 상향값은 통과. */
class DigestsSaturationAdvisorTest {

    private final DigestsSaturationAdvisor advisor = new DigestsSaturationAdvisor();

    private DbParameter p(String name, String value) {
        return new DbParameter(name, value, null);
    }

    @Test
    void MySQL만_지원한다() {
        assertTrue(advisor.supports(DbmsType.MYSQL));
        assertFalse(advisor.supports(DbmsType.POSTGRESQL));
        assertFalse(advisor.supports(DbmsType.ORACLE));
    }

    @Test
    void performance_schema_OFF는_치명이고_다른_지적을_덮는다() {
        List<AdvisorFinding> f = advisor.evaluate(List.of(
                p("performance_schema", "OFF"),
                p("performance_schema_digests_size", "10000")));
        assertEquals(1, f.size());
        assertEquals(Severity.CRITICAL, f.get(0).severity());
        assertTrue(f.get(0).title().contains("performance_schema"));
    }

    @Test
    void 기본_상한과_기본_digest_길이를_각각_잡는다() {
        List<AdvisorFinding> f = advisor.evaluate(List.of(
                p("performance_schema", "ON"),
                p("performance_schema_digests_size", "10000"),
                p("performance_schema_max_digest_length", "1024")));
        assertEquals(2, f.size());
        assertTrue(f.stream().anyMatch(x -> x.severity() == Severity.WARNING));
        assertTrue(f.stream().anyMatch(x -> x.severity() == Severity.INFO));
    }

    @Test
    void 상향된_설정은_통과() {
        List<AdvisorFinding> f = advisor.evaluate(List.of(
                p("performance_schema", "ON"),
                p("performance_schema_digests_size", "20000"),
                p("performance_schema_max_digest_length", "4096")));
        assertTrue(f.isEmpty());
    }
}
