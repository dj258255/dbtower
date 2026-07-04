package io.dbtower.operator;

/**
 * 쿼리 하나의 레이턴시 백분위(p95/p99) — DBTower 추상화 논지의 교과서적 사례(D4a).
 * "같은 지표라도 기종마다 원자료 가용성이 다르다"(same metric, different source). 그래서 값 자체가
 * 아니라 <b>그 값이 어디서 왔는지</b>를 source로 반드시 구분한다. 네 종류를 절대 섞지 않는다 —
 * 추정치를 실측인 척, 미지원을 지원하는 척하는 순간 이 기능은 거짓말이 된다.
 *
 * <ul>
 *   <li>NATIVE      — DB가 직접 계산해 제공하는 진짜 백분위. MySQL 8.0+
 *       events_statements_summary_by_digest의 QUANTILE_95/QUANTILE_99 컬럼(피코초→ms).
 *       <b>한계:</b> 이 값은 통계 리셋(서버 재기동/TRUNCATE) 이후 <b>누적</b> 백분위라 "최근 윈도우"가
 *       아니다 — 오래 뜬 서버일수록 과거 이력에 눌려 최근 급변을 늦게 반영한다. 응답·UI에 이 사실을 표기한다.</li>
 *   <li>COMPUTED    — 원시 샘플에서 우리가 직접 계산한 백분위. MongoDB system.profile의 op별 raw millis를
 *       정렬해 nearest-rank로 산출(프로파일러 레벨 2 전제). capped collection이라 최근 표본 위주라는 성질이 있다.</li>
 *   <li>ESTIMATED   — 백분위 원자료가 없어 평균+표준편차로 근사한 <b>추정치</b>. PostgreSQL
 *       pg_stat_statements의 mean_exec_time + z×stddev_exec_time(정규분포 가정, p95 z=1.645 / p99 z=2.326).
 *       실제 레이턴시 분포는 꼬리가 무거워(right-skewed) 이 근사는 대개 <b>과소평가</b>한다 — 진짜 백분위가 아니다.</li>
 *   <li>UNSUPPORTED — 백분위 원자료가 없어 계산도 정직한 근사도 불가능한 기종(SQL Server / Oracle).
 *       통계 뷰가 min/max/평균/총계만 제공하고 분위수·표준편차를 주지 않는다. 지원하는 척하지 않는다.</li>
 * </ul>
 *
 * p95Ms/p99Ms는 값을 낼 수 없으면 null이고, 그 이유는 source가 말한다.
 *
 * @param queryId   정규화된 쿼리 식별자(MySQL digest / PG queryid / Mongo queryHash). UNSUPPORTED 안내 행은 null
 * @param queryText 쿼리문(정규화 텍스트, 요약). UNSUPPORTED면 미지원 사유 문장
 * @param p95Ms     95 백분위 레이턴시(ms). 없으면 null
 * @param p99Ms     99 백분위 레이턴시(ms). 없으면 null
 * @param source    NATIVE / COMPUTED / ESTIMATED / UNSUPPORTED
 */
public record LatencyPercentile(String queryId, String queryText,
                                Double p95Ms, Double p99Ms, String source) {

    public static final String NATIVE = "NATIVE";
    public static final String COMPUTED = "COMPUTED";
    public static final String ESTIMATED = "ESTIMATED";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    /**
     * 백분위 원자료가 없는 기종의 정직한 단일 안내 행. p95/p99는 null이고 queryText에 사유를 담는다 —
     * 빈 목록으로 돌려주면 "데이터가 아직 없음"과 "이 기종은 원래 못 함"을 구분할 수 없어, 명시적 UNSUPPORTED 행으로 알린다.
     */
    public static LatencyPercentile unsupported(String reason) {
        return new LatencyPercentile(null, reason, null, null, UNSUPPORTED);
    }
}
