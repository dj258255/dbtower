package io.dbtower.operator.model;

/**
 * 정규화 쿼리 하나의 안티패턴 신호 (테마 C, VERIFICATION 158절) — "이 쿼리가 느린 이유의 성질"을 통계 뷰에서 읽는다.
 *
 * 느림의 크기는 {@link QueryStat}(호출·시간·행)이 답한다. 여기서 답하는 것은 성질이다 —
 * 인덱스 없이 훑는가, 디스크로 넘치는가, 한 행을 돌려주려고 얼마나 많이 읽는가. 실행계획을 뜨지 않고
 * 통계 뷰만으로 후보를 좁히는 값싼 신호이고, 판정(임계)은 서비스가 한다.
 *
 * 축은 기종에 공통이지만 <b>원천 지표 이름은 기종마다 다르다</b>. 그래서 값과 함께 그 기종이 실제로 읽은
 * 지표 이름을 담는다({@link RowsMetric}·{@link IndexUsage}와 같은 정직 규약) — 화면이 같은 이름으로
 * 다른 단위를 읽지 않게 하고, 없는 축은 지어내지 않고 null로 둔다.
 *
 * @param queryId        정규화 쿼리 식별자(digest·queryid·sql_id·queryHash)
 * @param calls          이 신호가 집계된 실행 수 — 비율의 분모다
 * @param fullScan       인덱스 없이 훑은 정도. 없으면 null(미확보를 0으로 위장하지 않는다)
 * @param diskSpill      정렬·임시 결과가 디스크로 넘친 정도
 * @param examinedPerRow 돌려준 행(문서) 하나당 읽은 양 — 선택도가 나쁜 쿼리의 대표 신호
 * @param source         {@link #NATIVE} 또는 {@link #UNSUPPORTED}
 * @param note           기종별 한계·집계 범위 설명(누적 카운터인지, 표본인지)
 */
public record QueryAntiPattern(String queryId, String queryText, long calls,
                               Metric fullScan, Metric diskSpill, Metric examinedPerRow,
                               String source, String note) {

    public static final String NATIVE = "NATIVE";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    /**
     * 한 축의 값과 그 값이 어디서 왔는지.
     *
     * @param sourceName 기종이 실제로 읽은 지표 이름(예: SUM_NO_INDEX_USED, temp_blks_written, total_spills)
     * @param value      값. 축 자체가 없는 기종은 null
     * @param unit       단위(회, 실행당, 블록, KB 등) — 숫자만 보고 단위를 추측하지 않게
     */
    public record Metric(String sourceName, Double value, String unit) {

        /** 이 기종에 그 축이 없을 때 — 값은 null이고 이름 자리에 사유를 담는다 */
        public static Metric absent(String reason) {
            return new Metric(reason, null, null);
        }
    }

    /**
     * 안티패턴 신호를 낼 수 없는 기종·환경의 단일 안내 행. 빈 목록으로 돌려주면 "안티패턴이 없음"과
     * "원래 판정 불가"를 구분할 수 없어, IndexUsage와 같은 방식으로 명시적 안내 행을 낸다.
     */
    public static QueryAntiPattern unsupported(String note) {
        return new QueryAntiPattern(null, null, 0, null, null, null, UNSUPPORTED, note);
    }
}
