package io.dbtower.score;

import io.dbtower.advisor.InstanceAdvisorReport;
import io.dbtower.backup.BackupFreshness;
import io.dbtower.insight.BaselineService.AnomalyScan;
import io.dbtower.registry.HealthStatus;
import io.dbtower.slo.SloReport;

/**
 * 한 신호가 헬스 스코어에 기여한 몫 (Phase D8) — "왜 이 점수인가"를 신호 단위로 분해해 응답에 노출한다(투명성).
 *
 * 상태(State)의 의미:
 * <ul>
 *   <li>{@code OK} — 신호를 읽었고 문제가 없다(감점 0, 점수 계산에 포함).</li>
 *   <li>{@code PENALIZED} — 신호를 읽었고 문제가 있다(감점 &gt; 0, 점수 계산에 포함).</li>
 *   <li>{@code INSUFFICIENT_DATA} — 데이터 부족으로 판정 보류(감점 없음, 점수에서 제외 · "부분 데이터" 표기).</li>
 *   <li>{@code ERROR} — 신호 수집 실패(격리, 감점 없음, 점수에서 제외 · "부분 데이터" 표기).</li>
 * </ul>
 * 핵심 구분: 데이터가 없는 것(INSUFFICIENT_DATA/ERROR)을 0점·장애로 오판하지 않는다. 감점은 실제로 나쁠 때만.
 *
 * @param signal  신호 종류
 * @param state   판정 상태
 * @param penalty 감점(0 이상). OK/INSUFFICIENT_DATA/ERROR는 0
 * @param summary 사람이 읽는 근거 한 줄
 */
public record SignalContribution(Signal signal, State state, double penalty, String summary) {

    /** 신호 종류 — health·이상감지·Advisors·SLO·백업 */
    public enum Signal { HEALTH, ANOMALY, ADVISOR, SLO, BACKUP }

    /** 신호 판정 상태 */
    public enum State { OK, PENALIZED, INSUFFICIENT_DATA, ERROR }

    /** 점수 계산에 포함되는가(감점을 낼 수 있는 상태인가) — 데이터 부족·에러는 제외 */
    public boolean counted() {
        return state == State.OK || state == State.PENALIZED;
    }

    /** 부분 데이터 사유인가(데이터 부족·수집 실패) */
    public boolean missing() {
        return state == State.INSUFFICIENT_DATA || state == State.ERROR;
    }

    static SignalContribution ok(Signal signal, String summary) {
        return new SignalContribution(signal, State.OK, 0, summary);
    }

    static SignalContribution penalized(Signal signal, double penalty, String summary) {
        return new SignalContribution(signal, State.PENALIZED, penalty, summary);
    }

    static SignalContribution insufficient(Signal signal, String summary) {
        return new SignalContribution(signal, State.INSUFFICIENT_DATA, 0, summary);
    }

    static SignalContribution error(Signal signal, String cause) {
        return new SignalContribution(signal, State.ERROR, 0, "수집 실패: " + cause);
    }

    // ---------- 신호별 순수 채점(테스트가 직접 고정) ----------
    // 각 팩토리는 해당 신호의 반환 구조만 보고 감점을 계산한다. 부수효과·조회 없음 → 단위 테스트로 산식 확정.

    /** health — down이 가장 무겁다(치명). down은 데이터 부족이 아니라 실제 나쁨이므로 감점한다. */
    static SignalContribution fromHealth(HealthStatus health, ScoreWeights w) {
        if (health.up()) {
            return ok(Signal.HEALTH, "가용 · ping " + health.pingMillis() + "ms");
        }
        return penalized(Signal.HEALTH, w.healthDown(), "인스턴스 다운: " + health.message());
    }

    /**
     * 이상 감지(D1) — 이상 쿼리 건수 비례 감점(상한 적용). 이상이 없으면 OK.
     * 절대 데이터 부족을 감점으로 바꾸지 않는다: 이상 감지기는 이력이 부족하면 스스로 "학습 중"으로 판정을
     * 보류하므로(빈 결과), 여기서는 보고된 이상만 센다. 학습 중 쿼리 수는 근거 문구로 투명하게 남긴다.
     */
    static SignalContribution fromAnomaly(AnomalyScan scan, ScoreWeights w) {
        int hits = scan.anomalies().size();
        String learning = scan.learningCount() > 0 ? " (학습 중 " + scan.learningCount() + ")" : "";
        if (hits == 0) {
            return ok(Signal.ANOMALY, "이상 없음" + learning);
        }
        double penalty = Math.min(hits * w.anomalyPerHit(), w.anomalyCap());
        return penalized(Signal.ANOMALY, penalty, "이상 쿼리 " + hits + "건" + learning);
    }

    /** Advisors(D2) — CRITICAL/WARNING 가중 합(상한 적용). INFO는 감점하지 않는다. */
    static SignalContribution fromAdvisor(InstanceAdvisorReport report, ScoreWeights w) {
        double raw = report.critical() * w.advisorCritical() + report.warning() * w.advisorWarning();
        double penalty = Math.min(raw, w.advisorCap());
        String summary = "치명 " + report.critical() + " · 경고 " + report.warning() + " · 정보 " + report.info();
        return penalty > 0 ? penalized(Signal.ADVISOR, penalty, summary) : ok(Signal.ADVISOR, summary);
    }

    /**
     * SLO/에러 버짓(D4) — 판정(verdict)으로 감점. INSUFFICIENT_DATA는 표본 부족이므로 "데이터 부족"으로
     * 격리하고 감점하지 않는다(없는 SLO를 장애로 오판 금지).
     */
    static SignalContribution fromSlo(SloReport report, ScoreWeights w) {
        String consumed = report.errorBudget() != null && report.errorBudget().budgetConsumedRatio() != null
                ? String.format(" · 버짓 소진 %.0f%%", report.errorBudget().budgetConsumedRatio() * 100)
                : "";
        return switch (report.verdict()) {
            case SloReport.BREACHING -> penalized(Signal.SLO, w.sloBreaching(), "SLO 위반" + consumed);
            case SloReport.AT_RISK -> penalized(Signal.SLO, w.sloAtRisk(), "SLO 위험" + consumed);
            case SloReport.MEETING -> ok(Signal.SLO, "SLO 충족" + consumed);
            default -> insufficient(Signal.SLO, "SLO 표본 부족 — 판정 보류");
        };
    }

    /**
     * 백업 신선도(D7) — NO_BACKUP(사각지대)·STALE는 실제 위험이므로 감점한다. FRESH는 OK.
     * NO_BACKUP은 "데이터 부족"이 아니라 "백업이 없다는 사실"(나쁨)이라 감점 대상이다.
     */
    static SignalContribution fromBackup(BackupFreshness backup, ScoreWeights w) {
        return switch (backup.status()) {
            case NO_BACKUP -> penalized(Signal.BACKUP, w.backupNoBackup(), "백업 없음 (사각지대)");
            case STALE -> penalized(Signal.BACKUP, w.backupStale(),
                    String.format("백업 오래됨 (%.1fh 경과 · 임계 %dh)", backup.elapsedHours(), backup.thresholdHours()));
            case FRESH -> ok(Signal.BACKUP, String.format("백업 신선 (%.1fh 경과)", backup.elapsedHours()));
        };
    }
}
