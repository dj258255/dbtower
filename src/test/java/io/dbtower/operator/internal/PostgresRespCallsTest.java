package io.dbtower.operator.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-4 "있으면 승격" — pg_stat_monitor resp_calls 히스토그램 승격 경로의 순수 로직을 커넥션 없이 못 박는다.
 * range() 문자열 파싱, resp_calls 버킷 요소합산, 그리고 공통 경계 + HistogramPercentile.interpolate로
 * p95가 기대 버킷 안에 드는지를 검증한다. 게이트 미통과(확장 없음 → ESTIMATED 유지)는 라이브로 확인한다.
 */
class PostgresRespCallsTest {

    // ── range() 문자열 파싱 유틸 ───────────────────────────────────────────────

    @Test
    void range_문자열은_하한_상한_두_숫자로_파싱된다() {
        assertThat(PostgresOperator.parseRange("(0 - 3)")).containsExactly(0.0, 3.0);
        assertThat(PostgresOperator.parseRange("(3 - 10)")).containsExactly(3.0, 10.0);
        assertThat(PostgresOperator.parseRange("(10 - 30)")).containsExactly(10.0, 30.0);
    }

    @Test
    void range_파싱은_괄호_공백_변형을_방어한다() {
        // 공백 없음 / 여백 과다 / 소수점 경계 — 숫자 토큰만 순서대로 취한다
        assertThat(PostgresOperator.parseRange("(0-3)")).containsExactly(0.0, 3.0);
        assertThat(PostgresOperator.parseRange("(  3  -  10  )")).containsExactly(3.0, 10.0);
        assertThat(PostgresOperator.parseRange("(0.5 - 1.5)")).containsExactly(0.5, 1.5);
        assertThat(PostgresOperator.parseRange("3 - 10")).containsExactly(3.0, 10.0);
    }

    @Test
    void range_파싱은_숫자가_부족하거나_null이면_거부한다() {
        // 승격 경로가 이 예외를 잡아 ESTIMATED로 폴백하게 되어 있다 — 조용히 0으로 위장하지 않는다
        assertThatThrownBy(() -> PostgresOperator.parseRange("(inf)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostgresOperator.parseRange(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── queryid별 resp_calls 요소합산 ─────────────────────────────────────────

    @Test
    void resp_calls는_여러_bucket_행이_요소별로_합산된다() {
        long[] bucketA = {1, 2, 3, 4};
        long[] bucketB = {10, 20, 30, 40};
        assertThat(PostgresOperator.sumElementwise(bucketA, bucketB))
                .containsExactly(11, 22, 33, 44);
    }

    @Test
    void resp_calls_요소합산은_길이가_달라도_긴쪽에_맞춘다() {
        // 버전차로 버킷 수가 다른 행이 섞여도 인덱스 예외 없이 병합한다
        long[] shortRow = {1, 2};
        long[] longRow = {10, 20, 30};
        assertThat(PostgresOperator.sumElementwise(shortRow, longRow))
                .containsExactly(11, 22, 30);
    }

    @Test
    void fitLength는_경계_길이에_맞춰_절단하거나_0패딩한다() {
        assertThat(PostgresOperator.fitLength(new long[]{1, 2, 3, 4, 5}, 3))
                .containsExactly(1, 2, 3);
        assertThat(PostgresOperator.fitLength(new long[]{1, 2}, 4))
                .containsExactly(1, 2, 0, 0);
        long[] same = {1, 2, 3};
        assertThat(PostgresOperator.fitLength(same, 3)).isSameAs(same);
    }

    // ── resp_calls 버킷 + 경계 → interpolate 백분위 ────────────────────────────

    @Test
    void p95는_카운트가_몰린_버킷_안의_값으로_보간된다() {
        // range() = 4개 버킷: (0-3),(3-10),(10-30),(30-100) ms
        double[] lower = boundsOf("(0 - 3)", "(3 - 10)", "(10 - 30)", "(30 - 100)", true);
        double[] upper = boundsOf("(0 - 3)", "(3 - 10)", "(10 - 30)", "(30 - 100)", false);

        // 대부분 첫 두 버킷에 몰리고, 상위 꼬리는 세 번째 버킷(10-30)에 있다
        // 총 100건: 60 + 35 + 5. 누적 95%(=95건)는 세 번째 버킷 안에서 교차한다
        long[] counts = {60, 35, 5, 0};
        Double p95 = HistogramPercentile.interpolate(lower, upper, counts, 0.95);

        assertThat(p95).isNotNull();
        assertThat(p95).isBetween(10.0, 30.0); // 세 번째 버킷(10-30) 안
    }

    @Test
    void p99는_꼬리_버킷에서_더_높게_보간된다() {
        double[] lower = boundsOf("(0 - 3)", "(3 - 10)", "(10 - 30)", "(30 - 100)", true);
        double[] upper = boundsOf("(0 - 3)", "(3 - 10)", "(10 - 30)", "(30 - 100)", false);
        long[] counts = {60, 35, 4, 1}; // 마지막 1건이 최상위 꼬리 버킷(30-100)

        Double p95 = HistogramPercentile.interpolate(lower, upper, counts, 0.95);
        Double p99 = HistogramPercentile.interpolate(lower, upper, counts, 0.99);

        assertThat(p95).isBetween(10.0, 30.0);
        assertThat(p99).isBetween(30.0, 100.0); // p99는 꼬리 버킷 안으로 더 높다
        assertThat(p99).isGreaterThan(p95);
    }

    @Test
    void 표본이_없으면_보간은_null이다() {
        double[] lower = boundsOf("(0 - 3)", "(3 - 10)", true);
        double[] upper = boundsOf("(0 - 3)", "(3 - 10)", false);
        assertThat(HistogramPercentile.interpolate(lower, upper, new long[]{0, 0}, 0.95))
                .isNull();
    }

    @Test
    void round2는_소수_둘째자리로_반올림한다() {
        assertThat(PostgresOperator.round2(13.2941)).isEqualTo(13.29);
        assertThat(PostgresOperator.round2(14.6555)).isEqualTo(14.66);
    }

    /** range() 원소 배열을 파싱해 lower(true)/upper(false) 경계 배열을 만든다 — 테스트 가독성용 헬퍼. */
    private static double[] boundsOf(Object... args) {
        boolean lower = (boolean) args[args.length - 1];
        double[] out = new double[args.length - 1];
        for (int i = 0; i < out.length; i++) {
            double[] b = PostgresOperator.parseRange((String) args[i]);
            out[i] = lower ? b[0] : b[1];
        }
        return out;
    }
}
