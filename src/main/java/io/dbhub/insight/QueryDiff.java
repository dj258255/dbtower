package io.dbhub.insight;

/**
 * 시점 비교 결과 한 줄.
 * 부하 상위 쿼리가 곧 범인이 아닐 수 있으므로(평소에도 높던 쿼리일 수 있다),
 * 평소 구간(base) 대비 문제 구간(target)의 증감률과 신규 유입 여부를 함께 보여준다.
 */
public record QueryDiff(
        String queryId,
        String queryText,
        double baseQps,
        double targetQps,
        Double qpsChangePct,      // base가 0이면 계산 불가 → null
        double baseAvgMs,
        double targetAvgMs,
        Double latencyChangePct,  // base가 0이면 계산 불가 → null
        boolean newQuery          // base 구간에 없다가 target 구간에 나타난 쿼리
) {
}
