package io.dbtower.score.internal;

import io.dbtower.registry.DbmsType;
import io.dbtower.score.internal.SignalContribution.Signal;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 전 인스턴스 요약의 나쁜 순 정렬·집계 검증 (Phase D8).
 * 죽은 인스턴스가 최상단에, 그다음 점수 낮은 순으로 오는지 — "어디부터 볼지"를 기계가 정렬하는지 고정한다.
 */
class HealthScoreReportTest {

    private final LocalDateTime now = LocalDateTime.now();

    private HealthScore score(long id, int downOrHealthy) {
        // downOrHealthy < 0 이면 health 다운 신호를 넣는다
        SignalContribution health = downOrHealthy < 0
                ? SignalContribution.penalized(Signal.HEALTH, 45, "다운")
                : SignalContribution.ok(Signal.HEALTH, "가용");
        SignalContribution extra = downOrHealthy > 0
                ? SignalContribution.penalized(Signal.BACKUP, downOrHealthy, "감점")
                : SignalContribution.ok(Signal.BACKUP, "신선");
        return HealthScore.of(id, "i" + id, DbmsType.MYSQL, now, List.of(health, extra));
    }

    @Test
    void 나쁜_순_정렬은_죽은것_먼저_그다음_점수_오름차순() {
        HealthScore healthy = score(1, 0);   // 100 A
        HealthScore mild = score(2, 12);     // 88 B
        HealthScore dead = score(3, -1);     // 55 F, down
        HealthScoreReport report = HealthScoreReport.of(now, List.of(healthy, mild, dead));

        // down 인스턴스가 최상단
        assertEquals(3L, report.instances().get(0).instanceId());
        assertTrue(report.instances().get(0).down());
        // 그다음 점수 낮은 순
        assertEquals(2L, report.instances().get(1).instanceId());
        assertEquals(1L, report.instances().get(2).instanceId());
    }

    @Test
    void 등급_분포와_부분데이터_수를_집계한다() {
        HealthScore healthy = score(1, 0);   // A
        HealthScore mild = score(2, 12);     // B
        HealthScore partial = HealthScore.of(3L, "p", DbmsType.POSTGRESQL, now, List.of(
                SignalContribution.ok(Signal.HEALTH, "가용"),
                SignalContribution.insufficient(Signal.SLO, "표본 부족")));
        HealthScoreReport report = HealthScoreReport.of(now, List.of(healthy, mild, partial));

        assertEquals(3, report.total());
        assertEquals(1, report.partialCount());
        assertEquals(2, report.gradeCounts().get(HealthScore.GRADE_A)); // healthy·partial 모두 100점=A
        assertEquals(1, report.gradeCounts().get(HealthScore.GRADE_B));
        assertEquals(0, report.gradeCounts().get(HealthScore.GRADE_F)); // 0인 등급도 키가 있다
    }
}
