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
 *   <li>NATIVE_WINDOWED — DB의 <b>누적</b> 히스토그램을 직전 스냅샷과 <b>버킷별 차분</b>해 복원한 "최근 윈도우"
 *       백분위. MySQL events_statements_histogram_by_digest(450버킷) 두 스냅샷 차이로 최근 구간 분포를 만들고,
 *       누적 95% 교차 버킷의 상한(BUCKET_TIMER_HIGH)을 p95로 쓴다. NATIVE(누적)와 달리 오래된 이력에
 *       눌리지 않고 최근 부하를 반영한다. <b>버킷 상한 근사</b>이므로 정확한 분위수가 아니라 상한 근사임을 표기한다.</li>
 *   <li>NATIVE_HISTOGRAM — DB가 제공하는 히스토그램 버킷을 우리가 <b>보간</b>해 얻은 백분위. Mongo serverStatus
 *       opLatencies·$collStats latencyStats(2^n 하한 버킷)의 스냅샷 차분 후 선형 보간, 또는 PostgreSQL
 *       pg_stat_monitor(있으면) resp_calls 버킷 배열 보간. 쿼리 단위가 아니라 인스턴스/컬렉션 단위일 수 있어
 *       그 범위를 함께 표기한다. 버킷 경계 보간이라 원자료 정밀도에 종속된다.</li>
 *   <li>COMPUTED    — 원시 샘플에서 우리가 직접 계산한 백분위. MongoDB system.profile의 op별 raw millis를
 *       정렬해 nearest-rank로 산출(프로파일러 레벨 2 전제). capped collection이라 최근 표본 위주라는 성질이 있다.</li>
 *   <li>ESTIMATED   — 백분위 원자료가 없어 평균+표준편차로 근사한 <b>추정치</b>. PostgreSQL
 *       pg_stat_statements의 mean_exec_time + z×stddev_exec_time(정규분포 가정, p95 z=1.645 / p99 z=2.326).
 *       SQL Server Query Store의 avg_duration + z×stdev_duration(구간 interval 재집계, max로 캡)도 같은 규약.
 *       실제 레이턴시 분포는 꼬리가 무거워(right-skewed) 이 근사는 대개 <b>과소평가</b>한다 — 진짜 백분위가 아니다.</li>
 *   <li>UNSUPPORTED — 백분위 원자료가 없어 계산도 정직한 근사도 불가능한 기종. Query Store가 꺼진 SQL Server,
 *       원자료(표준편차·히스토그램) 자체가 없는 Oracle v$sqlstats. 통계 뷰가 min/max/평균/총계만 준다.
 *       지원하는 척하지 않는다.</li>
 * </ul>
 *
 * p95Ms/p99Ms는 값을 낼 수 없으면 null이고, 그 이유는 source가 말한다.
 *
 * @param queryId   정규화된 쿼리 식별자(MySQL digest / PG queryid / Mongo queryHash). UNSUPPORTED 안내 행은 null
 * @param queryText 쿼리문(정규화 텍스트, 요약). UNSUPPORTED면 미지원 사유 문장
 * @param p95Ms     95 백분위 레이턴시(ms). 없으면 null
 * @param p99Ms     99 백분위 레이턴시(ms). 없으면 null
 * @param source    NATIVE / NATIVE_WINDOWED / NATIVE_HISTOGRAM / COMPUTED / ESTIMATED / UNSUPPORTED
 */
public record LatencyPercentile(String queryId, String queryText,
                                Double p95Ms, Double p99Ms, String source) {

    public static final String NATIVE = "NATIVE";
    public static final String NATIVE_WINDOWED = "NATIVE_WINDOWED";
    public static final String NATIVE_HISTOGRAM = "NATIVE_HISTOGRAM";
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
