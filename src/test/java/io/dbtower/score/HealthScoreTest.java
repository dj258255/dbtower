package io.dbtower.score;

import io.dbtower.registry.DbmsType;
import io.dbtower.score.SignalContribution.Signal;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 인스턴스 스코어 합산·등급·부분 데이터 처리 검증 (Phase D8).
 * 100점에서 감점을 빼고 0~100으로 바닥치는지, 데이터 부족 신호를 감점 없이 제외하며 partial로 표기하는지,
 * 감점 큰 신호가 분해 목록 위로 오는지, 등급 구간이 정확한지 고정한다.
 */
class HealthScoreTest {

    private final LocalDateTime now = LocalDateTime.now();

    private HealthScore build(SignalContribution... contributions) {
        return HealthScore.of(1L, "inst", DbmsType.MYSQL, now, List.of(contributions));
    }

    @Test
    void 감점을_합산해_100에서_뺀다() {
        HealthScore s = build(
                SignalContribution.ok(Signal.HEALTH, "가용"),
                SignalContribution.penalized(Signal.ADVISOR, 14, "치명1·경고2"),
                SignalContribution.penalized(Signal.BACKUP, 12, "오래됨"));
        assertEquals(74, s.score());          // 100 - 14 - 12
        assertEquals(HealthScore.GRADE_C, s.grade());
        assertFalse(s.partial());
        assertEquals(3, s.countedSignals());
    }

    @Test
    void 감점_합이_100을_넘어도_0에서_바닥친다() {
        HealthScore s = build(
                SignalContribution.penalized(Signal.HEALTH, 45, "다운"),
                SignalContribution.penalized(Signal.SLO, 25, "위반"),
                SignalContribution.penalized(Signal.ADVISOR, 30, "지적 다수"),
                SignalContribution.penalized(Signal.BACKUP, 20, "백업 없음"));
        assertEquals(0, s.score());
        assertEquals(HealthScore.GRADE_F, s.grade());
        assertTrue(s.down());   // health 감점 → down 플래그
    }

    @Test
    void 데이터_부족_신호는_감점없이_제외하고_partial로_표기한다() {
        // SLO 데이터 부족 + ERROR 신호가 있어도 나머지로 점수를 낸다("없음"을 0점/장애로 오판하지 않음)
        HealthScore s = build(
                SignalContribution.ok(Signal.HEALTH, "가용"),
                SignalContribution.penalized(Signal.BACKUP, 12, "오래됨"),
                SignalContribution.insufficient(Signal.SLO, "표본 부족"),
                SignalContribution.error(Signal.ADVISOR, "권한 부족"));
        assertEquals(88, s.score());           // 100 - 12, 부족/에러는 감점 0
        assertTrue(s.partial());
        assertEquals(2, s.countedSignals());   // health·backup만 계산에 포함
    }

    @Test
    void 분해_목록은_감점_큰_신호가_위로_온다() {
        HealthScore s = build(
                SignalContribution.ok(Signal.HEALTH, "가용"),
                SignalContribution.penalized(Signal.BACKUP, 12, "오래됨"),
                SignalContribution.penalized(Signal.SLO, 25, "위반"));
        assertEquals(Signal.SLO, s.contributions().get(0).signal());     // 25 감점 최상단
        assertEquals(Signal.BACKUP, s.contributions().get(1).signal());  // 12
        assertEquals(Signal.HEALTH, s.contributions().get(2).signal());  // 0
    }

    @Test
    void 등급_구간_경계() {
        assertEquals(HealthScore.GRADE_A, HealthScore.gradeOf(90));
        assertEquals(HealthScore.GRADE_B, HealthScore.gradeOf(89));
        assertEquals(HealthScore.GRADE_B, HealthScore.gradeOf(80));
        assertEquals(HealthScore.GRADE_C, HealthScore.gradeOf(79));
        assertEquals(HealthScore.GRADE_D, HealthScore.gradeOf(60));
        assertEquals(HealthScore.GRADE_F, HealthScore.gradeOf(59));
    }
}
