package io.dbhub.insight;

import io.dbhub.alert.AiAnalyzer;
import io.dbhub.analysis.RuleBasedAnalyzer;
import io.dbhub.operator.DbmsOperatorFactory;
import io.dbhub.operator.QueryStat;
import io.dbhub.operator.SlowQuery;
import io.dbhub.operator.TableStat;
import io.dbhub.registry.DatabaseInstance;
import io.dbhub.registry.RegistryService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/instances/{id}")
public class InsightController {

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final ComparisonService comparisonService;
    private final RuleBasedAnalyzer analyzer;
    private final AiAnalyzer aiAnalyzer;
    private final QuerySnapshotRepository snapshotRepository;

    public InsightController(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                             ComparisonService comparisonService, RuleBasedAnalyzer analyzer,
                             AiAnalyzer aiAnalyzer, QuerySnapshotRepository snapshotRepository) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.comparisonService = comparisonService;
        this.analyzer = analyzer;
        this.aiAnalyzer = aiAnalyzer;
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * load(점유율%) = 이 쿼리의 누적 수행시간 / 상위 N개 전체 수행시간.
     * 호출수가 아니라 "시간 점유율"로 랭킹해야 DB를 실제로 붙잡고 있는 쿼리가 보인다. (PMM QAN 방식)
     */
    public record QueryStatView(String queryId, String queryText, long calls,
                                double totalTimeMs, long rowsExamined, double loadPct) {
    }

    @GetMapping("/query-stats")
    public List<QueryStatView> queryStats(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        List<QueryStat> stats = operatorFactory.create(registryService.findById(id)).queryStats(limit);
        double totalTime = stats.stream().mapToDouble(QueryStat::totalTimeMs).sum();
        return stats.stream()
                .map(s -> new QueryStatView(s.queryId(), s.queryText(), s.calls(), s.totalTimeMs(),
                        s.rowsExamined(),
                        totalTime == 0 ? 0 : Math.round(s.totalTimeMs() / totalTime * 10000) / 100.0))
                .toList();
    }

    @GetMapping("/table-stats")
    public List<TableStat> tableStats(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return operatorFactory.create(registryService.findById(id)).tableStats(limit);
    }

    /** 복제 상태 통합 뷰 — SHOW REPLICA STATUS / pg_stat_replication / AlwaysOn DMV를 하나의 모델로 */
    @GetMapping("/replication")
    public io.dbhub.operator.ReplicationState replication(@PathVariable Long id) {
        return operatorFactory.create(registryService.findById(id)).replicationState();
    }

    @GetMapping("/slow-queries")
    public List<SlowQuery> slowQueries(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return operatorFactory.create(registryService.findById(id)).slowQueries(limit);
    }

    public record ExplainRequest(@NotBlank String sql) {
    }

    public record ExplainResponse(String plan, List<String> findings) {
    }

    /** 실행계획 + 규칙 기반 비효율 분석을 함께 돌려준다 */
    @PostMapping("/explain")
    public ExplainResponse explain(@PathVariable Long id, @RequestBody ExplainRequest req) {
        DatabaseInstance instance = registryService.findById(id);
        String plan = operatorFactory.create(instance).explain(req.sql());
        return new ExplainResponse(plan, analyzer.analyze(instance.getType(), plan));
    }

    /**
     * 시점 비교 — 평소 구간(base) 대비 문제 구간(target)의 쿼리별 QPS·평균 레이턴시 증감과 신규 쿼리.
     * 예: /api/instances/1/compare?baseFrom=2026-07-02T10:00&baseTo=2026-07-02T10:10&targetFrom=2026-07-02T14:00&targetTo=2026-07-02T14:10
     */
    @GetMapping("/compare")
    public ComparisonService.CompareResult compare(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime baseFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime baseTo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime targetFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime targetTo) {
        return comparisonService.compare(id, baseFrom, baseTo, targetFrom, targetTo);
    }

    /** 활동 그래프 한 점 — 배치 간 차분으로 계산한 그 구간의 QPS/평균 레이턴시 */
    public record ActivityPoint(LocalDateTime time, double qps, double avgLatencyMs) {
    }

    /**
     * 활동 그래프 (웹 UI의 구간 선택용) — KDMS가 CPU 그래프 위에서 조회/비교 구간을 드래그로
     * 고르게 한 것과 같은 역할. 우리는 수집 스냅샷에서 바로 뽑을 수 있는 QPS를 쓴다.
     * 원리는 시점 비교와 동일: 누적 카운터의 인접 배치 차분이 그 구간의 발생량이다.
     */
    @GetMapping("/activity")
    public List<ActivityPoint> activity(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<QuerySnapshotRepository.BatchTotal> batches = snapshotRepository.sumByBatch(id, from, to);
        List<ActivityPoint> points = new java.util.ArrayList<>();
        for (int i = 1; i < batches.size(); i++) {
            QuerySnapshotRepository.BatchTotal prev = batches.get(i - 1);
            QuerySnapshotRepository.BatchTotal cur = batches.get(i);
            long seconds = java.time.Duration.between(prev.getCapturedAt(), cur.getCapturedAt()).toSeconds();
            if (seconds <= 0) {
                continue;
            }
            // 카운터 리셋(대상 DB 재기동)이면 차분이 음수가 된다 — 0으로 클램프
            long calls = Math.max(0, cur.getTotalCalls() - prev.getTotalCalls());
            double timeMs = Math.max(0, cur.getTotalTimeMs() - prev.getTotalTimeMs());
            points.add(new ActivityPoint(cur.getCapturedAt(),
                    Math.round(calls * 100.0 / seconds) / 100.0,
                    calls == 0 ? 0 : Math.round(timeMs * 100.0 / calls) / 100.0));
        }
        return points;
    }

    public record AiAnalysisResponse(String plan, List<String> findings, String aiAnalysis) {
    }

    /**
     * EXPLAIN + 규칙 지적 + AI 1차 분석을 한 번에 — 웹 UI의 "AI 분석" 버튼용.
     * AI는 회귀 알림(RegressionDetector)과 동일한 판단 기준 프롬프트(ai-analysis-rules.md)를 쓴다.
     * ANTHROPIC_API_KEY 미설정이면 aiAnalysis=null로 규칙 지적까지만 내려간다.
     */
    @PostMapping("/ai-analysis")
    public AiAnalysisResponse aiAnalysis(@PathVariable Long id, @RequestBody ExplainRequest req) {
        DatabaseInstance instance = registryService.findById(id);
        String plan = operatorFactory.create(instance).explain(req.sql());
        List<String> findings = analyzer.analyze(instance.getType(), plan);
        String context = """
                [%s] 아래 쿼리와 실행계획을 판단 기준에 따라 분석해줘.
                SQL:
                %s
                실행계획:
                %s
                규칙 기반 지적: %s""".formatted(instance.getType(), req.sql(), plan,
                findings.isEmpty() ? "(없음)" : String.join(" / ", findings));
        return new AiAnalysisResponse(plan, findings, aiAnalyzer.analyze(context).orElse(null));
    }
}
