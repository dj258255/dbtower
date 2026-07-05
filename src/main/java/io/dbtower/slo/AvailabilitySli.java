package io.dbtower.slo;

/**
 * 가용성 SLI (Phase D4) — 헬스 샘플 이력에서 계산한 "윈도우 중 up 비율". 사용자 경험 지표(요청이 응답받을 확률).
 *
 * 회계 기간(windowDays) 안의 전체 샘플 대비 up 샘플 비율이 곧 관측 가용성이다. 표본이 최소치(minSamples)에
 * 못 미치면 판정을 보류한다(INSUFFICIENT_DATA) — 이력이 짧을 때 우연한 한두 번의 down을 과대 판정하지 않기 위해.
 *
 * @param windowDays   가용성 집계 윈도우(에러 버짓 회계 기간, 일)
 * @param totalSamples 윈도우 안의 전체 헬스 샘플 수
 * @param upSamples    그중 up이었던 샘플 수
 * @param upRatio      up 비율(0~1). 표본이 0이면 null
 * @param targetRatio  가용성 SLO 목표(예: 0.995)
 * @param minSamples   판정에 필요한 최소 표본 수(미만이면 데이터 부족)
 * @param verdict      MEETING/BREACHING/INSUFFICIENT_DATA
 * @param note         데이터 부족 등 정직한 상태 설명
 */
public record AvailabilitySli(int windowDays, long totalSamples, long upSamples, Double upRatio,
                              double targetRatio, int minSamples, String verdict, String note) {

    public static final String MEETING = "MEETING";
    public static final String BREACHING = "BREACHING";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
}
