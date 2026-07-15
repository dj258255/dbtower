package io.dbtower.operator.internal;

/**
 * 히스토그램 버킷에서 백분위를 뽑는 공용 헬퍼 (2차 아크 B-1 MySQL / B-3 Mongo / B-4 PG 공용).
 *
 * <p>레이턴시 원자료가 "분위수"가 아니라 "버킷별 카운트"로만 오는 기종이 있다(MySQL
 * events_statements_histogram_by_digest, Mongo opLatencies, PG pg_stat_monitor resp_calls).
 * 이 버킷들은 대개 <b>재기동 이후 누적</b>이라, 두 스냅샷을 {@link #windowDiff}로 차분하면
 * "최근 구간"의 분포가 되고, 거기서 백분위를 뽑는다. 뽑는 방식은 두 가지다 —
 *
 * <ul>
 *   <li>{@link #bucketCeiling} — 누적 교차 <b>버킷의 상한</b>을 그대로 백분위로. 버킷 상한이 곧
 *       "이 값 이하"의 보증선이라 상한 근사(대개 소폭 과대). MySQL BUCKET_TIMER_HIGH 방식(NATIVE_WINDOWED).</li>
 *   <li>{@link #interpolate} — 교차 버킷 <b>내부를 선형 보간</b>. 버킷 안 균등분포를 가정해 상·하한
 *       사이를 비례 배분. 버킷이 넓으면 오차가 커진다. Mongo/PG 히스토그램 방식(NATIVE_HISTOGRAM).</li>
 * </ul>
 *
 * <p>어느 쪽이든 <b>버킷 경계에 종속</b>된 근사이지 정확한 분위수가 아니라는 점이 라벨(NATIVE_WINDOWED /
 * NATIVE_HISTOGRAM)이 말하는 정직성의 핵심이다. 순수 계산 유틸 — 상태·부작용 없음.
 */
public final class HistogramPercentile {

    private HistogramPercentile() {
    }

    /**
     * 두 누적 스냅샷의 버킷별 차분 → 최근 구간 카운트. 카운터가 한 버킷이라도 감소했으면(재기동/리셋/
     * TRUNCATE) 신뢰할 수 없으므로 null을 반환한다 — 호출자는 이때 스냅샷을 폐기하고 폴백한다.
     *
     * @param prev 직전 스냅샷(없으면 null → null 반환, 첫 호출 신호)
     * @param cur  현재 스냅샷
     * @return 버킷별 (cur-prev). 길이 불일치·감소 감지 시 null
     */
    public static long[] windowDiff(long[] prev, long[] cur) {
        if (prev == null || cur == null || prev.length != cur.length) {
            return null;
        }
        long[] delta = new long[cur.length];
        for (int i = 0; i < cur.length; i++) {
            long d = cur[i] - prev[i];
            if (d < 0) {
                return null; // 카운터 감소 = 재기동/리셋 → 이번 구간 신뢰 불가
            }
            delta[i] = d;
        }
        return delta;
    }

    /**
     * 누적 교차 버킷의 상한을 백분위로 반환(BUCKET_TIMER_HIGH 방식). 총 카운트 0이면 null(구간에 표본 없음).
     *
     * @param upperBounds 버킷별 상한(오름차순, 단위는 호출자 규약)
     * @param counts      버킷별 카운트(윈도우 차분값)
     * @param quantile    0&lt;q&lt;1 (예: 0.95)
     */
    public static Double bucketCeiling(double[] upperBounds, long[] counts, double quantile) {
        long total = total(counts);
        if (total <= 0) {
            return null;
        }
        double threshold = quantile * total;
        long cum = 0;
        for (int i = 0; i < counts.length; i++) {
            cum += counts[i];
            if (cum >= threshold) {
                return upperBounds[i];
            }
        }
        return upperBounds[upperBounds.length - 1];
    }

    /**
     * 교차 버킷 내부를 선형 보간해 백분위 반환(균등분포 가정). 총 카운트 0이면 null.
     *
     * @param lowerBounds 버킷별 하한(오름차순)
     * @param upperBounds 버킷별 상한(오름차순, lowerBounds와 같은 길이)
     * @param counts      버킷별 카운트(윈도우 차분값)
     * @param quantile    0&lt;q&lt;1
     */
    public static Double interpolate(double[] lowerBounds, double[] upperBounds,
                                     long[] counts, double quantile) {
        long total = total(counts);
        if (total <= 0) {
            return null;
        }
        double threshold = quantile * total;
        long cum = 0;
        for (int i = 0; i < counts.length; i++) {
            long c = counts[i];
            if (cum + c >= threshold) {
                if (c == 0) {
                    return upperBounds[i];
                }
                double frac = (threshold - cum) / c; // 이 버킷 안에서의 위치 0..1
                return lowerBounds[i] + frac * (upperBounds[i] - lowerBounds[i]);
            }
            cum += c;
        }
        return upperBounds[upperBounds.length - 1];
    }

    private static long total(long[] counts) {
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        return total;
    }
}
