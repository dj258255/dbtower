package io.dbtower.advisor;

import io.dbtower.operator.TableStat;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 통계 미수집 후보 규칙 — "데이터는 있는데 통계상 0행"을 잡고, MongoDB는 미지원. */
class StaleStatisticsAdvisorTest {

    private final StaleStatisticsAdvisor advisor = new StaleStatisticsAdvisor();

    @Test
    void MongoDB만_미지원이고_관계형_4기종은_지원() {
        assertTrue(advisor.supports(DbmsType.MYSQL));
        assertTrue(advisor.supports(DbmsType.POSTGRESQL));
        assertTrue(advisor.supports(DbmsType.MSSQL));
        assertTrue(advisor.supports(DbmsType.ORACLE));
        assertFalse(advisor.supports(DbmsType.MONGODB));
    }

    @Test
    void 데이터_존재하는데_행수_0이면_잡는다() {
        List<AdvisorFinding> f = advisor.evaluate(List.of(
                new TableStat("orders", 0, 5 * 1024 * 1024, 1024 * 1024)));
        assertEquals(1, f.size());
        assertEquals(Severity.WARNING, f.get(0).severity());
        assertTrue(f.get(0).title().contains("orders"));
    }

    @Test
    void 행수가_있으면_통과() {
        List<AdvisorFinding> f = advisor.evaluate(List.of(
                new TableStat("orders", 12345, 5 * 1024 * 1024, 1024 * 1024)));
        assertTrue(f.isEmpty());
    }

    @Test
    void 데이터가_거의_없는_빈_테이블은_잡지_않는다() {
        List<AdvisorFinding> f = advisor.evaluate(List.of(
                new TableStat("tiny", 0, 4096, 0)));
        assertTrue(f.isEmpty());
    }
}
