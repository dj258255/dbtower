package io.dbtower.advisor;

import io.dbtower.advisor.internal.StatsCollectionAdvisor;
import io.dbtower.operator.model.StatsHealth;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 통계 수집 건강 판정 — "수집이 조용히 거짓말하는" 세 사각(포화·소실·PS)의 임계 로직.
 * 데모에서 포화를 재현할 수 없어(digests_size는 재기동 파라미터) 판정은 단위로 검증한다.
 */
class StatsCollectionAdvisorTest {

    private final StatsCollectionAdvisor advisor = new StatsCollectionAdvisor();

    @Test
    void MySQL_소실_발생이면_CRITICAL() {
        List<AdvisorFinding> f = advisor.evaluate(DbmsType.MYSQL,
                new StatsHealth(10_000, 10_000, 37, 0, 0, true, "n"));
        assertThat(f).anyMatch(x -> x.severity() == Severity.CRITICAL && x.title().contains("소실"));
    }

    @Test
    void MySQL_포화_80퍼센트_이상이면_WARNING_미만이면_침묵() {
        assertThat(advisor.evaluate(DbmsType.MYSQL, new StatsHealth(8_000, 10_000, 0, 0, 0, true, "n")))
                .anyMatch(x -> x.severity() == Severity.WARNING && x.title().contains("포화"));
        assertThat(advisor.evaluate(DbmsType.MYSQL, new StatsHealth(45, 10_000, 0, 0, 0, true, "n")))
                .isEmpty();
    }

    @Test
    void MySQL_PS_실행이_크면_WARNING_있기만_하면_INFO() {
        assertThat(advisor.evaluate(DbmsType.MYSQL, new StatsHealth(45, 10_000, 0, 3, 50_000, true, "n")))
                .anyMatch(x -> x.severity() == Severity.WARNING && x.title().contains("Prepared Statement"));
        assertThat(advisor.evaluate(DbmsType.MYSQL, new StatsHealth(45, 10_000, 0, 2, 10, true, "n")))
                .anyMatch(x -> x.severity() == Severity.INFO && x.title().contains("Prepared Statement"));
    }

    @Test
    void PG_evict_발생이면_WARNING_사용률만_높으면_INFO() {
        assertThat(advisor.evaluate(DbmsType.POSTGRESQL, new StatsHealth(5_000, 5_000, 12, -1, -1, true, "n")))
                .anyMatch(x -> x.severity() == Severity.WARNING && x.title().contains("evict"));
        assertThat(advisor.evaluate(DbmsType.POSTGRESQL, new StatsHealth(4_200, 5_000, 0, -1, -1, true, "n")))
                .anyMatch(x -> x.severity() == Severity.INFO && x.title().contains("사용률"));
        assertThat(advisor.evaluate(DbmsType.POSTGRESQL, new StatsHealth(750, 5_000, 0, -1, -1, true, "n")))
                .isEmpty();
    }

    @Test
    void 미지원_실측이면_지어내지_않고_침묵() {
        assertThat(advisor.evaluate(DbmsType.MYSQL, StatsHealth.unsupported("x"))).isEmpty();
    }
}
