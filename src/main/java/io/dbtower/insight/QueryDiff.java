package io.dbtower.insight;

/**
 * 시점 비교 결과 한 줄.
 * 부하 상위 쿼리가 곧 범인이 아닐 수 있으므로(평소에도 높던 쿼리일 수 있다),
 * 평소 구간(base) 대비 문제 구간(target)의 증감률과 신규 유입 여부를 함께 보여준다.
 *
 * rowsPerCall(호출당 읽는 행수)의 급증은 실행계획 변화·파라미터 폭증(IN절 수천 개 등)의
 * 대리 신호다 — MySQL/PG 통계 소스에는 플랜 해시가 없어 직접 감지 대신 이 지표를 쓴다.
 */
public record QueryDiff(
        String queryId,
        String queryText,
        double baseQps,
        double targetQps,
        Double qpsChangePct,          // base가 0이면 계산 불가 → null
        double baseAvgMs,
        double targetAvgMs,
        Double latencyChangePct,
        double baseRowsPerCall,
        double targetRowsPerCall,
        Double rowsPerCallChangePct,
        boolean newQuery              // base 구간에 없다가 target 구간에 나타난 쿼리
) {
}
