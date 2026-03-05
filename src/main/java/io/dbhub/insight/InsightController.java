package io.dbhub.insight;

import io.dbhub.analysis.RuleBasedAnalyzer;
import io.dbhub.operator.DbmsOperatorFactory;
import io.dbhub.operator.QueryStat;
import io.dbhub.operator.SlowQuery;
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

    public InsightController(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                             ComparisonService comparisonService, RuleBasedAnalyzer analyzer) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.comparisonService = comparisonService;
        this.analyzer = analyzer;
    }

    @GetMapping("/query-stats")
    public List<QueryStat> queryStats(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return operatorFactory.create(registryService.findById(id)).queryStats(limit);
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
}
