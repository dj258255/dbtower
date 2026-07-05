package io.dbtower.slo;

/**
 * 에러 버짓 + 번인 레이트 (Phase D4) — 가용성 SLO 기준 Google SRE 모델.
 *
 * <b>버짓 산식(응답·주석에 명시):</b>
 * <ul>
 *   <li>허용 다운타임 = (1 - target) × windowDays × 24 × 60 분.
 *       예) target 99.5%, window 30일 → 0.005 × 30 × 24 × 60 ≈ 216분(월 3.6시간).</li>
 *   <li>관측 다운타임 ≈ down 샘플 수 × 샘플 간격(sampleIntervalMinutes). 주기 관측이라 down 샘플 하나는
 *       대략 한 주기만큼의 다운타임을 뜻한다(근사). 이력이 회계 기간보다 짧으면 그만큼만 관측된 부분 합이다.</li>
 *   <li>버짓 소진율 = 관측 다운타임 / 허용 다운타임. 1.0 이상이면 버짓 초과(EXHAUSTED).</li>
 *   <li><b>번인 레이트</b> = 최근 창(burnWindowMinutes)의 다운 비율 / (1 - target). 지속 가능 속도 대비 배수 —
 *       1.0이면 회계 기간에 버짓을 정확히 다 쓰는 속도, 2.0이면 두 배로 태워 절반 기간에 소진한다.</li>
 * </ul>
 *
 * 표본이 최소치에 못 미치면 INSUFFICIENT_DATA로 판정을 보류한다.
 *
 * @param targetRatio              가용성 SLO 목표
 * @param windowDays               버짓 회계 기간(일)
 * @param sampleIntervalMinutes    헬스 샘플 간격(분) — 다운 샘플→다운타임 환산 계수
 * @param allowedDowntimeMinutes   회계 기간 허용 다운타임(분)
 * @param observedDowntimeMinutes  관측 다운타임(분, 근사)
 * @param budgetConsumedRatio      버짓 소진율(0~). 데이터 부족이면 null
 * @param budgetRemainingRatio     버짓 잔여율(1 - 소진율, 음수 가능). 데이터 부족이면 null
 * @param burnWindowMinutes        번인 레이트 최근 창(분)
 * @param burnRate                 번인 레이트(지속 가능 속도 대비 배수). 최근 창 표본 부족이면 null
 * @param verdict                  OK/WARNING/EXHAUSTED/INSUFFICIENT_DATA
 * @param note                     산식·한계 설명
 */
public record ErrorBudget(double targetRatio, int windowDays, double sampleIntervalMinutes,
                          double allowedDowntimeMinutes, double observedDowntimeMinutes,
                          Double budgetConsumedRatio, Double budgetRemainingRatio,
                          int burnWindowMinutes, Double burnRate,
                          String verdict, String note) {

    public static final String OK = "OK";
    public static final String WARNING = "WARNING";
    public static final String EXHAUSTED = "EXHAUSTED";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
}
