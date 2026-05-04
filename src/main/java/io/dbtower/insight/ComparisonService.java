package io.dbtower.insight;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 시점 비교 — 평소 구간(base)과 문제 구간(target)의 쿼리별 통계를 차분해 비교한다.
 *
 * 원리: queryStats의 calls/totalTimeMs는 서버 기동 이후 누적 카운터다.
 * 그래서 구간 안의 "첫 배치"와 "마지막 배치"의 차이가 그 구간 동안 실제 발생한 양이 된다.
 * 구간 길이가 서로 달라도 비교할 수 있도록 QPS(초당 호출수)로 정규화한다.
 */
@Service
public class ComparisonService {

    private final QuerySnapshotRepository snapshotRepository;

    public ComparisonService(QuerySnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    /** 구간 전체 요약 — 쿼리별 표를 읽기 전에 "전반적으로 무엇이 변했는지"부터 보여준다 */
    public record WindowSummary(long totalCalls, double totalTimeMs, double avgLatencyMs,
                                long totalRowsExamined, int queryCount) {
    }

    public record CompareResult(WindowSummary base, WindowSummary target,
                                Double totalCallsChangePct, Double avgLatencyChangePct,
                                Double rowsExaminedChangePct, int newQueryCount,
                                List<QueryDiff> queries) {
    }

    public CompareResult compare(Long instanceId,
                                 LocalDateTime baseFrom, LocalDateTime baseTo,
                                 LocalDateTime targetFrom, LocalDateTime targetTo) {
        Map<String, WindowStat> base = windowStats(instanceId, baseFrom, baseTo);
        Map<String, WindowStat> target = windowStats(instanceId, targetFrom, targetTo);

        List<QueryDiff> diffs = new ArrayList<>();
        int newQueryCount = 0;
        for (Map.Entry<String, WindowStat> e : target.entrySet()) {
            String queryId = e.getKey();
            WindowStat t = e.getValue();
            WindowStat b = base.get(queryId);
            boolean isNew = (b == null);
            if (isNew) {
                newQueryCount++;
            }

            double baseQps = isNew ? 0 : b.qps();
            double baseAvgMs = isNew ? 0 : b.avgMs();
            double baseRowsPerCall = isNew ? 0 : b.rowsPerCall();
            diffs.add(new QueryDiff(
                    queryId,
                    t.queryText,
                    round(baseQps), round(t.qps()), changePct(baseQps, t.qps()),
                    round(baseAvgMs), round(t.avgMs()), changePct(baseAvgMs, t.avgMs()),
                    round(baseRowsPerCall), round(t.rowsPerCall()), changePct(baseRowsPerCall, t.rowsPerCall()),
                    isNew));
        }
        // 문제 구간에서 시간을 가장 많이 쓴 쿼리부터
        diffs.sort(Comparator.comparingDouble((QueryDiff d) -> d.targetQps() * d.targetAvgMs()).reversed());

        WindowSummary baseSummary = summarize(base);
        WindowSummary targetSummary = summarize(target);
        return new CompareResult(
                baseSummary, targetSummary,
                changePct(baseSummary.totalCalls(), targetSummary.totalCalls()),
                changePct(baseSummary.avgLatencyMs(), targetSummary.avgLatencyMs()),
                changePct(baseSummary.totalRowsExamined(), targetSummary.totalRowsExamined()),
                newQueryCount,
                diffs);
    }

    private WindowSummary summarize(Map<String, WindowStat> stats) {
        long totalCalls = stats.values().stream().mapToLong(WindowStat::deltaCalls).sum();
        double totalTimeMs = stats.values().stream().mapToDouble(WindowStat::deltaTimeMs).sum();
        long totalRows = stats.values().stream().mapToLong(WindowStat::deltaRows).sum();
        double avgLatency = totalCalls == 0 ? 0 : totalTimeMs / totalCalls;
        return new WindowSummary(totalCalls, round(totalTimeMs), round(avgLatency), totalRows, stats.size());
    }

    /** 구간 양 끝 배치의 누적 카운터 차분으로 구간 내 발생량을 구한다 */
    private Map<String, WindowStat> windowStats(Long instanceId, LocalDateTime from, LocalDateTime to) {
        List<QuerySnapshot> rows =
                snapshotRepository.findByInstanceIdAndCapturedAtBetweenOrderByCapturedAt(instanceId, from, to);

        // capturedAt 기준으로 배치를 나눈다
        TreeMap<LocalDateTime, List<QuerySnapshot>> batches = rows.stream()
                .collect(Collectors.groupingBy(QuerySnapshot::getCapturedAt, TreeMap::new, Collectors.toList()));
        if (batches.size() < 2) {
            throw new IllegalArgumentException(
                    "구간 [" + from + " ~ " + to + "] 안에 스냅샷 배치가 2개 이상 필요합니다 (현재 " + batches.size() + "개)");
        }

        Map<String, QuerySnapshot> first = byQueryId(batches.firstEntry().getValue());
        Map<String, QuerySnapshot> last = byQueryId(batches.lastEntry().getValue());
        long windowSeconds = Math.max(1,
                Duration.between(batches.firstKey(), batches.lastKey()).getSeconds());

        Map<String, WindowStat> result = new HashMap<>();
        for (Map.Entry<String, QuerySnapshot> e : last.entrySet()) {
            QuerySnapshot end = e.getValue();
            QuerySnapshot start = first.get(e.getKey());
            // 구간 중간에 처음 나타난 쿼리는 카운터 전체를 구간 발생량으로 본다
            long deltaCalls = start == null ? end.getCalls() : Math.max(0, end.getCalls() - start.getCalls());
            double deltaTimeMs = start == null ? end.getTotalTimeMs()
                    : Math.max(0, end.getTotalTimeMs() - start.getTotalTimeMs());
            long deltaRows = start == null ? end.getRowsExamined()
                    : Math.max(0, end.getRowsExamined() - start.getRowsExamined());
            if (deltaCalls == 0) {
                continue; // 이 구간에 실행되지 않은 쿼리는 비교 대상이 아니다
            }
            result.put(e.getKey(), new WindowStat(end.getQueryText(), deltaCalls, deltaTimeMs, deltaRows, windowSeconds));
        }
        return result;
    }

    private Map<String, QuerySnapshot> byQueryId(List<QuerySnapshot> batch) {
        return batch.stream().collect(Collectors.toMap(QuerySnapshot::getQueryId, s -> s, (a, b) -> a));
    }

    private Double changePct(double before, double after) {
        if (before == 0) {
            return null;
        }
        return round((after - before) / before * 100);
    }

    private double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private record WindowStat(String queryText, long deltaCalls, double deltaTimeMs,
                              long deltaRows, long windowSeconds) {
        double qps() {
            return (double) deltaCalls / windowSeconds;
        }

        double avgMs() {
            return deltaCalls == 0 ? 0 : deltaTimeMs / deltaCalls;
        }

        double rowsPerCall() {
            return deltaCalls == 0 ? 0 : (double) deltaRows / deltaCalls;
        }
    }
}
