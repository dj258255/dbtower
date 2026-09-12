package io.dbtower.operator.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿼리 안티패턴 신호 모델의 정직 규약(158절) — 없는 축을 0으로 위장하지 않고, 판정 불가를 빈 목록으로 숨기지 않는다.
 * 값 자체는 기종 SQL이 채우지만, "무엇이 미확보인가"를 구분하는 책임은 이 모델에 있다.
 */
class QueryAntiPatternTest {

    @Test
    void 축이_없는_기종은_값이_null이고_사유가_이름_자리에_남는다() {
        QueryAntiPattern.Metric absent = QueryAntiPattern.Metric.absent("DMV에 인덱스 미사용 카운터 없음");

        assertThat(absent.value()).isNull();
        assertThat(absent.unit()).isNull();
        assertThat(absent.sourceName()).contains("없음");
    }

    @Test
    void 판정_불가는_빈_목록이_아니라_안내_행으로_알린다() {
        QueryAntiPattern row = QueryAntiPattern.unsupported("프로파일러가 꺼져 표본이 없다");

        assertThat(row.source()).isEqualTo(QueryAntiPattern.UNSUPPORTED);
        assertThat(row.note()).contains("프로파일러");
        // 값 자리는 전부 비어 있어야 한다 — 0으로 채우면 "안티패턴 없음"으로 읽힌다
        assertThat(row.queryId()).isNull();
        assertThat(row.fullScan()).isNull();
        assertThat(row.diskSpill()).isNull();
        assertThat(row.examinedPerRow()).isNull();
        assertThat(row.calls()).isZero();
    }

    @Test
    void 값이_있는_행은_지표_이름과_단위를_함께_담는다() {
        QueryAntiPattern row = new QueryAntiPattern("digest-1", "SELECT ...", 100,
                new QueryAntiPattern.Metric("SUM_NO_INDEX_USED + SUM_SELECT_FULL_JOIN", 1.0, "실행당"),
                new QueryAntiPattern.Metric("SUM_CREATED_TMP_DISK_TABLES + SUM_SORT_MERGE_PASSES", 0.25, "실행당"),
                new QueryAntiPattern.Metric("SUM_ROWS_EXAMINED / SUM_ROWS_SENT", 1200.0, "검사행/반환행"),
                QueryAntiPattern.NATIVE, "performance_schema digest 누적");

        assertThat(row.source()).isEqualTo(QueryAntiPattern.NATIVE);
        // 화면이 같은 이름으로 다른 단위를 읽지 않게, 축마다 원천 지표 이름과 단위가 붙는다
        assertThat(row.fullScan().sourceName()).isEqualTo("SUM_NO_INDEX_USED + SUM_SELECT_FULL_JOIN");
        assertThat(row.examinedPerRow().unit()).isEqualTo("검사행/반환행");
        assertThat(row.diskSpill().value()).isEqualTo(0.25);
    }
}
