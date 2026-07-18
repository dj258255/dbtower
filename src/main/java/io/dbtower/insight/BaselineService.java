package io.dbtower.insight;

import io.dbtower.insight.internal.BaselineLongtermDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 이상 자동 감지 — "평소 대비" 동적 베이스라인 (Phase D1).
 *
 * RegressionDetector는 "직전 구간 대비 +200%" 같은 고정 임계로 회귀를 잡는다. 그건 급격한 스파이크는
 * 잘 잡지만 "이 쿼리가 원래 이 시간대엔 느린가"를 모른다 — 매일 아침 배치가 도는 08시대의 높은 QPS를
 * 회귀로 오인하거나, 반대로 평소 조용하던 쿼리가 서서히 무거워지는 저하는 임계 밑에서 놓친다.
 *
 * 이 서비스는 AWS DevOps Guru for RDS 모델을 따른다: 과거 스냅샷 이력에서 인스턴스·쿼리별로
 * (요일 × 시간대) 베이스라인 통계(평균·표준편차)를 학습하고, 현재 구간 값이 그 분포에서 z-score로
 * 얼마나 벗어났는지로 이상을 판정한다. "평소의 이 시간대"를 기준으로 보므로 주기적 부하를 오탐하지 않는다.
 *
 * 정체성 가드레일: 읽고 판단만 한다. 대상 DB를 바꾸지 않고, 스냅샷(누적 카운터 차분) 기반이라 5기종 공통이다.
 */
@Service
public class BaselineService {

    private final QuerySnapshotRepository snapshotRepository;
    private final BaselineLongtermDao longtermDao;

    /** 베이스라인 학습에 쓸 과거 이력 길이(일). 이 안의 "같은 요일·시간대" 관측만 베이스라인에 들어간다 */
    private final int historyDays;
    /** 현재 구간 길이(분) — 이 창의 쿼리별 값이 베이스라인과 비교된다 */
    private final int recentMinutes;
    /** 이상 판정 임계 z-score(표준편차 몇 배 이탈). AWS 계열이 흔히 쓰는 3σ를 기본값으로 */
    private final double zThreshold;
    /** 버킷당 최소 관측 수 — 이보다 적으면 "학습 중"으로 판정 보류(신규 인스턴스·쿼리 오탐 방지) */
    private final int minObservations;
    /** 장기 베이스라인(lakehouse 되쓰기) 병합 스위치 — 끄면 D8 이전과 완전히 동일하게 동작 */
    private final boolean longtermEnabled;

    public BaselineService(QuerySnapshotRepository snapshotRepository,
                           BaselineLongtermDao longtermDao,
                           @Value("${dbtower.baseline.history-days:14}") int historyDays,
                           @Value("${dbtower.baseline.recent-minutes:5}") int recentMinutes,
                           @Value("${dbtower.baseline.z-threshold:3.0}") double zThreshold,
                           @Value("${dbtower.baseline.min-observations:8}") int minObservations,
                           @Value("${dbtower.baseline.longterm-enabled:true}") boolean longtermEnabled) {
        this.snapshotRepository = snapshotRepository;
        this.longtermDao = longtermDao;
        this.historyDays = historyDays;
        this.recentMinutes = recentMinutes;
        this.zThreshold = zThreshold;
        this.minObservations = minObservations;
        this.longtermEnabled = longtermEnabled;
    }

    // ---------- 노이즈 게이트 ----------
    // 표준편차가 0에 가까운(이력이 비현실적으로 안정적인) 버킷에서 아주 미세한 변화가 무한대 z를 만드는 것을
    // 막는 "상대 표준편차 바닥". 유효 표준편차 = max(관측 표준편차, 평균×이 비율)이라, 이력이 완전히
    // 평평해도 z=3을 넘으려면 현재값이 평균보다 최소 3×0.15=45% 이상 커야 한다. 오탐을 눌러준다.
    private static final double MIN_REL_STDDEV = 0.15;
    // 메트릭별 절대 유의 바닥 — 평균/현재가 이 밑이면 비율 변화가 커도 무시(RegressionDetector의 최소 게이트와 같은 취지).
    // 예: QPS 0.05짜리 한산한 쿼리가 0.15로 튀어도 실무적으론 이상이 아니다.
    private static final double MIN_QPS = 0.1;
    private static final double MIN_AVG_MS = 1.0;
    private static final double MIN_ROWS_PER_CALL = 10.0;

    /** 베이스라인 통계 한 메트릭 — 관측 수·평균·표준편차 */
    public record BaselineStat(long observations, double mean, double stddev) {
    }

    /** 한 메트릭의 이상 — 현재값이 베이스라인에서 z-score만큼 벗어났다 */
    public record MetricAnomaly(String metric, double current, double baselineMean,
                                double baselineStddev, double zScore) {
    }

    /** 쿼리 하나의 판정 결과. baselineAvailable=false면 이력 부족으로 "학습 중"(이상 판정 보류) */
    public record QueryAnomaly(String queryId, String queryText, int dayOfWeek, int hour,
                               long observations, boolean baselineAvailable,
                               List<MetricAnomaly> anomalies) {
    }

    /**
     * 인스턴스 현재 이상 스캔 결과 — 이상 쿼리 목록과 함께 학습 중(이력 부족) 쿼리 수를 정직하게 노출한다.
     * dayOfWeek/hour는 "어느 요일·시간대 베이스라인과 비교했는지"를 화면·알림에 밝히기 위한 컨텍스트.
     */
    public record AnomalyScan(Long instanceId, int dayOfWeek, int hour,
                              int minObservations, double zThreshold,
                              List<QueryAnomaly> anomalies, int learningCount) {
    }

    /**
     * 현재 구간을 (지금 요일·시간대의) 베이스라인과 비교해 이상 쿼리 목록을 낸다.
     * 이상이 없어도(또는 이력 부족이어도) 예외 없이 빈/학습중 결과를 반환한다 — 폴러가 매 주기 안전히 호출한다.
     */
    /**
     * 최근 창(recent-minutes)의 쿼리별 현재 QPS — Top Query 표의 Call/sec 컬럼용.
     * 누적 카운터는 단일 스냅샷으로 초당 호출을 낼 수 없어(창이 필요), 최근 두 스냅샷 배치의 차분으로 계산한다.
     * 스냅샷 이력이 2배치 미만이면 빈 맵(화면은 "—"로 정직 표기 — 지어내지 않는다).
     */
    public Map<String, Double> recentQps(Long instanceId, LocalDateTime now) {
        Map<String, Obs> window = currentWindow(instanceId, now.minusMinutes(recentMinutes), now);
        Map<String, Double> qps = new HashMap<>();
        window.forEach((queryId, obs) -> qps.put(queryId, obs.qps()));
        return qps;
    }

    public AnomalyScan detectAnomalies(Long instanceId, LocalDateTime now) {
        LocalDateTime currentFrom = now.minusMinutes(recentMinutes);
        DayOfWeek dow = now.getDayOfWeek();
        int hour = now.getHour();

        // 현재 구간의 쿼리별 값(누적 카운터 양 끝 차분). 배치가 2개 미만이면 비교 자체가 불가 → 빈 결과.
        Map<String, Obs> current = currentWindow(instanceId, currentFrom, now);
        if (current.isEmpty()) {
            return new AnomalyScan(instanceId, dow.getValue(), hour, minObservations, zThreshold, List.of(), 0);
        }

        // 베이스라인은 현재 창을 제외한 과거 이력에서만 학습한다 — 지금의 이상 데이터가 자기 베이스라인을 오염시키면 안 된다.
        Map<String, QueryAcc> baseline =
                buildBaseline(instanceId, now.minusDays(historyDays), currentFrom, dow, hour);

        // 장기 베이스라인 병합(D8, lakehouse reverse ETL) — 14일 창은 주간 계절성 버킷의 관측이
        // 최대 2개라 "매주 월요일 아침 피크"를 오탐한다. lakehouse가 수개월 이력으로 계산해
        // baseline_longterm에 되써 준 (dow, hour) 통계를 QPS 축에 가중 병합한다.
        // 단위 정합: 장기는 "시간당 delta_calls" — QPS로 /3600 스케일(선형이라 mean·stddev 동일 배율).
        // 요일 규약: lakehouse dow는 일=0..토=6, java DayOfWeek는 월=1..일=7 → getValue()%7.
        // 미존재/빈 테이블이면 병합할 것이 없어 D8 이전과 완전히 동일하게 동작한다(회귀 0 계약).
        Map<String, BaselineLongtermDao.LongtermStat> longterm =
                longtermEnabled ? longtermDao.findBucket(instanceId, dow.getValue() % 7, hour) : Map.of();

        List<QueryAnomaly> anomalies = new ArrayList<>();
        int learning = 0;
        for (Map.Entry<String, Obs> e : current.entrySet()) {
            Obs obs = e.getValue();
            QueryAcc acc = baseline.get(e.getKey());
            // 단기 관측 수를 병합 전에 기억한다 — 레이턴시·행수 통계는 단기 창에서만 나오므로,
            // 그 두 메트릭의 판정 가능 여부는 단기 n이 정한다(장기가 늘리는 건 QPS 축뿐).
            long shortN = acc == null ? 0 : acc.qps.n;

            BaselineLongtermDao.LongtermStat lt = longterm.get(e.getKey());
            if (lt != null) {
                if (acc == null) {
                    acc = new QueryAcc(); // 단기 이력이 전무해도 장기가 아는 쿼리 — 장기만으로 QPS 판정 가능
                }
                acc.qps.addSummary(lt.observations(), lt.meanDeltaCalls() / 3600.0, lt.stddevDeltaCalls() / 3600.0);
            }
            long n = acc == null ? 0 : acc.qps.n; // QPS 축의 (병합 후) 관측 수

            if (n < minObservations) {
                // 이력 부족 — 오탐을 만드느니 판정을 보류하고 "학습 중"으로만 표기한다.
                learning++;
                continue;
            }

            List<MetricAnomaly> hits = new ArrayList<>();
            evaluate(hits, "qps", obs.qps, acc.qps, MIN_QPS);
            if (shortN >= minObservations) {
                // 장기 통계는 호출량(QPS)만 나른다 — 레이턴시·행수는 단기 관측이 충분할 때만 판정한다
                // (얕은 단기 표본으로 판정하면 장기 병합이 오히려 오탐 문을 여는 꼴).
                evaluate(hits, "latencyMs", obs.avgMs, acc.avgMs, MIN_AVG_MS);
                evaluate(hits, "rowsPerCall", obs.rowsPerCall, acc.rows, MIN_ROWS_PER_CALL);
            }
            if (!hits.isEmpty()) {
                anomalies.add(new QueryAnomaly(e.getKey(), obs.queryText, dow.getValue(), hour, n, true, hits));
            }
        }
        // 가장 심하게 벗어난(최대 z) 쿼리부터
        anomalies.sort(Comparator.comparingDouble(BaselineService::maxZ).reversed());
        return new AnomalyScan(instanceId, dow.getValue(), hour, minObservations, zThreshold, anomalies, learning);
    }

    private static double maxZ(QueryAnomaly a) {
        return a.anomalies().stream().mapToDouble(MetricAnomaly::zScore).max().orElse(0);
    }

    /**
     * 한 메트릭 이상 판정. 저하/부하 방향(현재 > 평균)만 이상으로 본다 — 레이턴시·행수·QPS가 "평소보다
     * 낮아진" 것은 성능 문제가 아니므로(가용성 급락은 별도 신호의 몫) 여기선 다루지 않는다.
     */
    private void evaluate(List<MetricAnomaly> out, String metric, double current, Acc acc, double floor) {
        double mean = acc.mean();
        if (mean < floor || current < floor) {
            return; // 절대값이 유의미하지 않으면(한산한 쿼리) 비율 변화가 커도 무시
        }
        double effStddev = Math.max(acc.stddev(), mean * MIN_REL_STDDEV);
        double z = (current - mean) / effStddev;
        if (z >= zThreshold && current > mean) {
            out.add(new MetricAnomaly(metric, round(current), round(mean), round(acc.stddev()), round(z)));
        }
    }

    /**
     * 과거 이력에서 (요일·시간대) 버킷의 쿼리별 통계를 누적한다.
     * 시간대는 DB에서 걸러 받고(findForHourBucket), 요일은 여기서 거른다. 인접 배치 차분 하나가 관측 하나다.
     */
    private Map<String, QueryAcc> buildBaseline(Long instanceId, LocalDateTime from, LocalDateTime to,
                                                DayOfWeek dow, int hour) {
        List<QuerySnapshot> rows = snapshotRepository.findForHourBucket(instanceId, from, to, hour);
        TreeMap<LocalDateTime, List<QuerySnapshot>> batches = batchesByTime(rows);

        Map<String, QueryAcc> baseline = new HashMap<>();
        LocalDateTime prevKey = null;
        Map<String, QuerySnapshot> prev = null;
        for (Map.Entry<LocalDateTime, List<QuerySnapshot>> entry : batches.entrySet()) {
            LocalDateTime curKey = entry.getKey();
            Map<String, QuerySnapshot> cur = byQueryId(entry.getValue());
            // 관측의 소속 버킷은 구간 끝(curKey) 기준. 요일이 다르면(같은 시간대라도) 이 버킷 아니다.
            if (prev != null && curKey.getDayOfWeek() == dow) {
                long seconds = Math.max(1, Duration.between(prevKey, curKey).getSeconds());
                for (Obs obs : diffBatch(prev, cur, seconds)) {
                    QueryAcc acc = baseline.computeIfAbsent(obs.queryId, k -> new QueryAcc());
                    acc.queryText = obs.queryText;
                    acc.qps.add(obs.qps);
                    acc.avgMs.add(obs.avgMs);
                    acc.rows.add(obs.rowsPerCall);
                }
            }
            prevKey = curKey;
            prev = cur;
        }
        return baseline;
    }

    /** 현재 구간의 쿼리별 값 — 창 양 끝 배치 차분. 배치 2개 미만이면 비교 불가라 빈 맵. */
    private Map<String, Obs> currentWindow(Long instanceId, LocalDateTime from, LocalDateTime to) {
        List<QuerySnapshot> rows =
                snapshotRepository.findByInstanceIdAndCapturedAtBetweenOrderByCapturedAt(instanceId, from, to);
        TreeMap<LocalDateTime, List<QuerySnapshot>> batches = batchesByTime(rows);
        if (batches.size() < 2) {
            return Map.of();
        }
        long seconds = Math.max(1, Duration.between(batches.firstKey(), batches.lastKey()).getSeconds());
        Map<String, Obs> result = new HashMap<>();
        for (Obs obs : diffBatch(byQueryId(batches.firstEntry().getValue()),
                byQueryId(batches.lastEntry().getValue()), seconds)) {
            result.put(obs.queryId, obs);
        }
        return result;
    }

    /**
     * 두 배치(누적 카운터)의 차분 → 쿼리별 관측. ComparisonService와 같은 원리:
     * 카운터 리셋(대상 DB 재기동)으로 음수가 되면 0으로 클램프하고, 실행 안 된 쿼리는 뺀다.
     */
    private List<Obs> diffBatch(Map<String, QuerySnapshot> start, Map<String, QuerySnapshot> end, long seconds) {
        List<Obs> out = new ArrayList<>();
        for (Map.Entry<String, QuerySnapshot> e : end.entrySet()) {
            QuerySnapshot to = e.getValue();
            QuerySnapshot from = start.get(e.getKey());
            long deltaCalls = from == null ? to.getCalls() : Math.max(0, to.getCalls() - from.getCalls());
            if (deltaCalls == 0) {
                continue;
            }
            double deltaTime = from == null ? to.getTotalTimeMs() : Math.max(0, to.getTotalTimeMs() - from.getTotalTimeMs());
            long deltaRows = from == null ? to.getRowsExamined() : Math.max(0, to.getRowsExamined() - from.getRowsExamined());
            out.add(new Obs(e.getKey(), to.getQueryText(),
                    (double) deltaCalls / seconds, deltaTime / deltaCalls, (double) deltaRows / deltaCalls));
        }
        return out;
    }

    private TreeMap<LocalDateTime, List<QuerySnapshot>> batchesByTime(List<QuerySnapshot> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                QuerySnapshot::getCapturedAt, TreeMap::new, Collectors.toList()));
    }

    private Map<String, QuerySnapshot> byQueryId(List<QuerySnapshot> batch) {
        return batch.stream().collect(Collectors.toMap(QuerySnapshot::getQueryId, s -> s, (a, b) -> a));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** 한 인접 배치 차분에서 나온 쿼리 하나의 관측값 */
    private record Obs(String queryId, String queryText, double qps, double avgMs, double rowsPerCall) {
    }

    /** 한 쿼리의 세 메트릭 누적기 */
    private static final class QueryAcc {
        final Acc qps = new Acc();
        final Acc avgMs = new Acc();
        final Acc rows = new Acc();
        String queryText;
    }

    /**
     * 온라인 평균·표본표준편차 누적기(합·제곱합). 표준편차는 n<2면 0.
     * 표본분산 = (Σx² − n·평균²)/(n−1); 부동소수 오차로 음수가 되면 0으로 클램프.
     */
    private static final class Acc {
        private long n;
        private double sum;
        private double sumSq;

        void add(double v) {
            n++;
            sum += v;
            sumSq += v * v;
        }

        /**
         * 요약 통계(관측 수·평균·표본표준편차)를 충분통계량으로 복원해 병합한다(D8 장기 병합).
         * Σx = n·mean, Σx² = (n−1)·s² + n·mean² — 원시 관측을 일일이 더한 것과 수학적으로 동일하므로
         * 단기 관측과 장기 요약의 가중 결합이 자동으로 성립한다(가중치 = 관측 수).
         */
        void addSummary(long count, double mean, double stddev) {
            if (count <= 0) {
                return;
            }
            n += count;
            sum += mean * count;
            sumSq += (count - 1) * stddev * stddev + count * mean * mean;
        }

        double mean() {
            return n == 0 ? 0 : sum / n;
        }

        double stddev() {
            if (n < 2) {
                return 0;
            }
            double m = mean();
            double var = (sumSq - n * m * m) / (n - 1);
            return var <= 0 ? 0 : Math.sqrt(var);
        }
    }
}
