package io.dbtower.insight.internal.web;

import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.DeepAnalyzer;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.analysis.RuleBasedAnalyzer;
import io.dbtower.insight.BaselineService;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.PrometheusClient;
import io.dbtower.insight.QuerySnapshotRepository;
import io.dbtower.insight.internal.AiAnalysisRunner;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsightControllerQueryStatsTest {

    @Test
    void 실시간_누적값이_원천_정렬과_달라져도_Load_내림차순으로_응답한다() {
        RegistryService registry = mock(RegistryService.class);
        DbmsOperatorFactory factory = mock(DbmsOperatorFactory.class);
        BaselineService baseline = mock(BaselineService.class);
        DatabaseInstance instance = mock(DatabaseInstance.class);
        DbmsOperator operator = mock(DbmsOperator.class);
        when(registry.findById(7L)).thenReturn(instance);
        when(factory.create(instance)).thenReturn(operator);
        when(operator.queryStats(20)).thenReturn(List.of(
                new QueryStat("a", "SELECT 1", 2, 20, 4),
                new QueryStat("b", "CREATE ROLE demo PASSWORD 'real-secret'", 5, 50, 15),
                new QueryStat("c", "SELECT 3", 3, 30, 6)));
        when(baseline.recentQps(any(Long.class), any(LocalDateTime.class))).thenReturn(Map.of());

        InsightController controller = new InsightController(registry, factory,
                mock(ComparisonService.class), mock(RuleBasedAnalyzer.class), mock(AiAnalyzer.class),
                mock(DeepAnalyzer.class), mock(QuerySnapshotRepository.class), baseline,
                mock(PrometheusClient.class), mock(QueryMasker.class), mock(AiAnalysisRunner.class));

        List<InsightController.QueryStatView> result = controller.queryStats(7L, 20);

        assertThat(result).extracting(InsightController.QueryStatView::queryId)
                .containsExactly("b", "c", "a");
        assertThat(result).extracting(InsightController.QueryStatView::loadPct)
                .containsExactly(50.0, 30.0, 20.0);
        assertThat(result.getFirst().queryText()).isEqualTo("CREATE ROLE demo PASSWORD ?");
        assertThat(result).extracting(InsightController.QueryStatView::queryText)
                .allSatisfy(text -> assertThat(text).doesNotContain("real-secret"));
    }
}
