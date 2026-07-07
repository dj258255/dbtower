package io.dbtower.operator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 히스토그램 백분위 공용 헬퍼 검증 (2차 아크 B-1 MySQL / B-3 Mongo / B-4 PG 공용).
 * 원칙: 누적 스냅샷 차분으로 "최근 구간"을 만들고(재기동은 폐기), 그 구간 분포에서 백분위를 뽑는다.
 * bucketCeiling은 교차 버킷의 상한(상한 근사), interpolate는 버킷 내부 선형 보간.
 */
class HistogramPercentileTest {

    // ---------- windowDiff: 누적 스냅샷 차분 ----------

    @Test
    void 첫_호출은_직전_스냅샷이_없어_null() {
        assertThat(HistogramPercentile.windowDiff(null, new long[]{1, 2, 3})).isNull();
    }

    @Test
    void 정상_증가는_버킷별_차분() {
        long[] prev = {10, 20, 30};
        long[] cur = {12, 25, 30};
        assertThat(HistogramPercentile.windowDiff(prev, cur)).containsExactly(2, 5, 0);
    }

    @Test
    void 카운터_감소는_재기동으로_보고_null() {
        long[] prev = {10, 20, 30};
        long[] cur = {10, 5, 31}; // 둘째 버킷이 줄었다 = 리셋/재기동
        assertThat(HistogramPercentile.windowDiff(prev, cur)).isNull();
    }

    @Test
    void 길이가_다르면_null() {
        assertThat(HistogramPercentile.windowDiff(new long[]{1, 2}, new long[]{1, 2, 3})).isNull();
    }

    // ---------- bucketCeiling: 교차 버킷 상한 (MySQL NATIVE_WINDOWED) ----------

    @Test
    void 총카운트_0이면_null() {
        double[] ups = {1, 2, 3};
        assertThat(HistogramPercentile.bucketCeiling(ups, new long[]{0, 0, 0}, 0.95)).isNull();
    }

    @Test
    void p95는_누적_95퍼_교차_버킷의_상한() {
        // 100개: 90개가 첫 버킷(<=1ms), 9개가 둘째(<=2ms), 1개가 셋째(<=3ms).
        double[] ups = {1, 2, 3};
        long[] counts = {90, 9, 1};
        // 누적 95% = 95번째. 90+9=99 >= 95 → 둘째 버킷 상한 2ms.
        assertThat(HistogramPercentile.bucketCeiling(ups, counts, 0.95)).isEqualTo(2.0);
        // p99 = 99번째 → 여전히 둘째 버킷(누적 99) 상한 2ms.
        assertThat(HistogramPercentile.bucketCeiling(ups, counts, 0.99)).isEqualTo(2.0);
    }

    @Test
    void 같은_구조_다른_스케일도_상한만_본다() {
        // 카운트 분포가 같으면 총량이 10배여도 같은 교차 버킷.
        double[] ups = {5, 50, 500};
        assertThat(HistogramPercentile.bucketCeiling(ups, new long[]{95, 4, 1}, 0.95))
                .isEqualTo(HistogramPercentile.bucketCeiling(ups, new long[]{950, 40, 10}, 0.95));
    }

    // ---------- interpolate: 버킷 내부 선형 보간 (Mongo/PG NATIVE_HISTOGRAM) ----------

    @Test
    void 보간은_교차_버킷_안을_비례배분() {
        // 한 버킷 [0,10ms]에 100개 균등 → p95는 버킷의 95% 지점 ~9.5ms.
        double[] low = {0};
        double[] up = {10};
        Double p95 = HistogramPercentile.interpolate(low, up, new long[]{100}, 0.95);
        assertThat(p95).isEqualTo(9.5);
    }

    @Test
    void 보간_두_버킷_경계_넘어가기() {
        // [0,10):50개, [10,20):50개. p95=95번째 → 둘째 버킷에서 (95-50)/50=0.9 → 10+0.9*10=19.
        double[] low = {0, 10};
        double[] up = {10, 20};
        Double p95 = HistogramPercentile.interpolate(low, up, new long[]{50, 50}, 0.95);
        assertThat(p95).isEqualTo(19.0);
    }

    @Test
    void 보간_총카운트_0이면_null() {
        assertThat(HistogramPercentile.interpolate(new double[]{0}, new double[]{1},
                new long[]{0}, 0.95)).isNull();
    }
}
