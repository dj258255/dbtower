package io.dbtower.score;

/**
 * 헬스 스코어 감점 가중치 (Phase D8) — 각 신호가 100점에서 얼마를 깎는지 명시한다.
 *
 * 설계 의도(응답·주석에 명시하는 산식):
 * <ul>
 *   <li><b>health down</b>이 가장 무겁다(치명). 인스턴스가 죽으면 나머지 신호가 무의미하므로 단독으로 F에
 *       근접시키도록 크게 잡는다.</li>
 *   <li><b>advisor·anomaly</b>는 건수 비례 감점에 상한(cap)을 둔다 — 지적이 쏟아져도 한 신호가 점수를
 *       독식하지 않게(다른 신호의 목소리를 남긴다).</li>
 *   <li><b>SLO·backup</b>은 등급/상태에 따라 고정 감점.</li>
 * </ul>
 * 데이터 부족(SLO 표본 부족 등)은 감점이 아니라 "부분 데이터"로 격리하므로 여기에 가중치가 없다.
 *
 * 값은 application.yml(dbtower.score.weights)에서 오버라이드한다. defaults()는 테스트·폴백 기본값.
 *
 * @param healthDown      health가 down일 때 감점(치명)
 * @param anomalyPerHit   이상 쿼리 1건당 감점
 * @param anomalyCap      이상 감지 신호의 감점 상한
 * @param advisorCritical Advisor CRITICAL 1건당 감점
 * @param advisorWarning  Advisor WARNING 1건당 감점
 * @param advisorCap      Advisor 신호의 감점 상한
 * @param sloBreaching    SLO 위반(BREACHING) 감점
 * @param sloAtRisk       SLO 위험(AT_RISK) 감점
 * @param backupNoBackup  백업 없음(NO_BACKUP, 사각지대) 감점
 * @param backupStale     백업 오래됨(STALE) 감점
 */
public record ScoreWeights(double healthDown,
                           double anomalyPerHit, double anomalyCap,
                           double advisorCritical, double advisorWarning, double advisorCap,
                           double sloBreaching, double sloAtRisk,
                           double backupNoBackup, double backupStale) {

    /** 기본 가중치 — 100점 만점 기준. 합산 최대 감점이 100을 넘어도 점수는 0에서 바닥친다. */
    public static ScoreWeights defaults() {
        return new ScoreWeights(45, 4, 16, 8, 3, 30, 25, 10, 20, 12);
    }
}
