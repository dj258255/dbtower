package io.dbtower.score;

import io.dbtower.advisor.InstanceAdvisorReport;
import io.dbtower.backup.BackupFreshness;
import io.dbtower.insight.BaselineService.AnomalyScan;
import io.dbtower.insight.BaselineService.QueryAnomaly;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.HealthStatus;
import io.dbtower.score.SignalContribution.Signal;
import io.dbtower.score.SignalContribution.State;
import io.dbtower.slo.ErrorBudget;
import io.dbtower.slo.SloReport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 신호별 감점 산식 검증 (Phase D8) — 각 신호의 반환 구조를 감점으로 옮기는 순수 함수를 고정한다.
 * 특히 "데이터 부족 vs 나쁨"의 갈림(SLO 표본 부족은 감점 없이 격리, 백업 없음은 실제 위험이라 감점)을 못박는다.
 */
class SignalContributionTest {

    private final ScoreWeights w = ScoreWeights.defaults();

    private AnomalyScan scan(int hits, int learning) {
        List<QueryAnomaly> anomalies = new ArrayList<>();
        for (int i = 0; i < hits; i++) {
            anomalies.add(new QueryAnomaly("q" + i, "SELECT " + i, 1, 10, 100, true, List.of()));
        }
        return new AnomalyScan(1L, 1, 10, 5, 3.0, anomalies, learning);
    }

    private BackupFreshness backup(BackupFreshness.Status status, Double elapsed) {
        return new BackupFreshness(1L, "b", DbmsType.MYSQL, null, null, elapsed,
                status == BackupFreshness.Status.FRESH, status, 24);
    }

    // ---------- health ----------

    @Test
    void health가_up이면_감점_없음() {
        SignalContribution c = SignalContribution.fromHealth(HealthStatus.up("8.0", 3), w);
        assertEquals(State.OK, c.state());
        assertEquals(0, c.penalty());
    }

    @Test
    void health가_down이면_치명_감점() {
        SignalContribution c = SignalContribution.fromHealth(HealthStatus.down("접속 실패"), w);
        assertEquals(State.PENALIZED, c.state());
        assertEquals(w.healthDown(), c.penalty());
        assertEquals(Signal.HEALTH, c.signal());
    }

    // ---------- 이상 감지 ----------

    @Test
    void 이상_없으면_OK_학습중은_문구로만_남긴다() {
        SignalContribution c = SignalContribution.fromAnomaly(scan(0, 3), w);
        assertEquals(State.OK, c.state());
        assertEquals(0, c.penalty());
        assertTrue(c.summary().contains("학습 중 3"));
    }

    @Test
    void 이상_건수만큼_감점하되_상한을_넘지_않는다() {
        assertEquals(2 * w.anomalyPerHit(), SignalContribution.fromAnomaly(scan(2, 0), w).penalty());
        // 상한(anomalyCap) 초과 건수는 상한에서 잘린다
        assertEquals(w.anomalyCap(), SignalContribution.fromAnomaly(scan(100, 0), w).penalty());
    }

    // ---------- Advisors ----------

    @Test
    void advisor는_치명_경고_가중합이고_상한을_적용한다() {
        // 치명 1·경고 2 = 8 + 6 = 14
        InstanceAdvisorReport r = new InstanceAdvisorReport(1L, "a", DbmsType.MYSQL,
                LocalDateTime.now(), List.of(), 1, 2, 5);
        SignalContribution c = SignalContribution.fromAdvisor(r, w);
        assertEquals(State.PENALIZED, c.state());
        assertEquals(1 * w.advisorCritical() + 2 * w.advisorWarning(), c.penalty());

        // 지적이 쏟아져도 상한에서 잘린다
        InstanceAdvisorReport flood = new InstanceAdvisorReport(1L, "a", DbmsType.MYSQL,
                LocalDateTime.now(), List.of(), 20, 20, 0);
        assertEquals(w.advisorCap(), SignalContribution.fromAdvisor(flood, w).penalty());
    }

    @Test
    void advisor_지적이_없으면_OK고_INFO만_있으면_감점_없다() {
        InstanceAdvisorReport info = new InstanceAdvisorReport(1L, "a", DbmsType.MYSQL,
                LocalDateTime.now(), List.of(), 0, 0, 7);
        SignalContribution c = SignalContribution.fromAdvisor(info, w);
        assertEquals(State.OK, c.state());
        assertEquals(0, c.penalty());
    }

    // ---------- SLO: 데이터 부족 vs 나쁨 ----------

    private SloReport slo(String verdict, ErrorBudget budget) {
        return new SloReport(1L, "s", DbmsType.MYSQL, LocalDateTime.now(), null, null, budget, verdict);
    }

    @Test
    void slo_위반은_감점_충족은_OK() {
        assertEquals(w.sloBreaching(), SignalContribution.fromSlo(slo(SloReport.BREACHING, null), w).penalty());
        assertEquals(w.sloAtRisk(), SignalContribution.fromSlo(slo(SloReport.AT_RISK, null), w).penalty());
        assertEquals(State.OK, SignalContribution.fromSlo(slo(SloReport.MEETING, null), w).state());
    }

    @Test
    void slo_표본_부족은_감점이_아니라_데이터_부족으로_격리한다() {
        SignalContribution c = SignalContribution.fromSlo(slo(SloReport.INSUFFICIENT_DATA, null), w);
        assertEquals(State.INSUFFICIENT_DATA, c.state());
        assertEquals(0, c.penalty());
        assertFalse(c.counted());   // 점수 계산에서 제외
        assertTrue(c.missing());    // 부분 데이터 사유
    }

    // ---------- 백업: 없음/오래됨은 나쁨(감점) ----------

    @Test
    void 백업_없음은_데이터부족이_아니라_사각지대_감점이다() {
        SignalContribution c = SignalContribution.fromBackup(backup(BackupFreshness.Status.NO_BACKUP, null), w);
        assertEquals(State.PENALIZED, c.state());
        assertEquals(w.backupNoBackup(), c.penalty());
        assertTrue(c.counted());
    }

    @Test
    void 백업_오래됨은_STALE_감점_신선은_OK() {
        assertEquals(w.backupStale(),
                SignalContribution.fromBackup(backup(BackupFreshness.Status.STALE, 40.0), w).penalty());
        assertEquals(State.OK,
                SignalContribution.fromBackup(backup(BackupFreshness.Status.FRESH, 2.0), w).state());
    }
}
