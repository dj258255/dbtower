package io.dbtower.slo;

/**
 * 레이턴시 SLI (Phase D4) — "인프라 지표가 아니라 사용자 경험 지표"(SRE 원칙). D4a의 latencyPercentiles를 재사용한다.
 *
 * 인스턴스 레이턴시 SLI = 상위 부하 쿼리들 중 <b>가장 나쁜 p95</b>(꼬리의 꼬리). 한 핵심 쿼리라도 임계를 넘으면
 * 사용자가 느린 것이므로, 평균으로 뭉개지 않고 최악값으로 판정한다. 값의 출처(source)를 절대 감추지 않는다:
 *
 * <ul>
 *   <li>NATIVE/COMPUTED/ESTIMATED — D4a가 공급한 p95/p99를 그대로 SLI로 쓴다(source 유지). PostgreSQL의
 *       ESTIMATED는 정규분포 근사라 과소평가 가능함을 note로 표기한다.</li>
 *   <li>AVG_FALLBACK — 백분위 원자료가 없는 기종(SQL Server/Oracle, D4a UNSUPPORTED)은 평균 레이턴시로
 *       폴백한다(queryStats의 sum(totalTimeMs)/sum(calls)). 꼬리를 못 보므로 낙관적일 수 있음을 note로 표기.
 *       p95Ms/p99Ms는 null이고 observedMs에 평균이 담긴다 — 폴백을 실측 백분위인 척하지 않는다.</li>
 *   <li>INSUFFICIENT_DATA — 쿼리 통계 자체가 없어 SLI를 낼 수 없는 상태(verdict로도 표기).</li>
 * </ul>
 *
 * @param source               NATIVE/COMPUTED/ESTIMATED/AVG_FALLBACK/INSUFFICIENT_DATA
 * @param observedMs           SLO 임계와 비교하는 값(백분위면 최악 p95, 폴백이면 평균). 없으면 null
 * @param p95Ms                최악 p95(ms) — 폴백/데이터부족이면 null
 * @param p99Ms                그 쿼리의 p99(ms) — 폴백/데이터부족이면 null
 * @param thresholdMs          레이턴시 SLO 목표(p95 임계, ms)
 * @param coreQueryId          최악 p95를 낸 핵심 쿼리 식별자(폴백/데이터부족이면 null)
 * @param coreQueryText        그 쿼리문(요약). 폴백/데이터부족이면 null
 * @param breachingCoreQueries 상위 부하 쿼리 중 p95가 임계를 넘은 개수(폴백은 평균>임계면 1, 아니면 0)
 * @param totalCoreQueries     평가한 핵심 쿼리 수(백분위 행 수 — 폴백이면 0)
 * @param verdict              MEETING/BREACHING/INSUFFICIENT_DATA
 * @param note                 출처·한계에 대한 정직한 설명
 */
public record LatencySli(String source, Double observedMs, Double p95Ms, Double p99Ms,
                         double thresholdMs, String coreQueryId, String coreQueryText,
                         int breachingCoreQueries, int totalCoreQueries,
                         String verdict, String note) {

    public static final String AVG_FALLBACK = "AVG_FALLBACK";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    public static final String MEETING = "MEETING";
    public static final String BREACHING = "BREACHING";
}
