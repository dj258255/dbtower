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
import io.dbtower.operator.model.SlowQuery;
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

    private static InsightController controller(RegistryService registry, DbmsOperatorFactory factory,
                                                QueryMasker queryMasker) {
        return new InsightController(registry, factory,
                mock(ComparisonService.class), mock(RuleBasedAnalyzer.class), mock(AiAnalyzer.class),
                mock(DeepAnalyzer.class), mock(QuerySnapshotRepository.class), mock(BaselineService.class),
                mock(PrometheusClient.class), queryMasker, mock(AiAnalysisRunner.class),
                mock(io.dbtower.insight.internal.CollectionStatusStore.class));
    }

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

        InsightController controller = controller(registry, factory, mock(QueryMasker.class));

        List<InsightController.QueryStatView> result = controller.queryStats(7L, 20);

        assertThat(result).extracting(InsightController.QueryStatView::queryId)
                .containsExactly("b", "c", "a");
        assertThat(result).extracting(InsightController.QueryStatView::loadPct)
                .containsExactly(50.0, 30.0, 20.0);
        assertThat(result.getFirst().queryText()).isEqualTo("CREATE ROLE demo PASSWORD ?");
        assertThat(result).extracting(InsightController.QueryStatView::queryText)
                .allSatisfy(text -> assertThat(text).doesNotContain("real-secret"));
    }

    /**
     * 슬로우 쿼리도 값 리터럴을 가려서 돌려준다 — 이 경로만 빠져 있어 AI 진단이 이 도구를 부르면
     * MySQL slow_log·MongoDB system.profile의 값이 그대로 나갔다. 화면도 같은 응답을 쓰므로 표에서도 가려진다.
     */
    @Test
    void 느린_쿼리_문장도_리터럴을_가려서_돌려준다() {
        RegistryService registry = mock(RegistryService.class);
        DbmsOperatorFactory factory = mock(DbmsOperatorFactory.class);
        DatabaseInstance instance = mock(DatabaseInstance.class);
        DbmsOperator operator = mock(DbmsOperator.class);
        when(registry.findById(7L)).thenReturn(instance);
        when(factory.create(instance)).thenReturn(operator);
        when(operator.slowQueries(20)).thenReturn(List.of(
                new SlowQuery("SELECT * FROM users WHERE email = 'leak@example.com' AND id = 424242", 12.0, 5,
                        "2026-09-16T10:00:00"),
                new SlowQuery("SELECT * FROM orders", 1.0, 1, "2026-09-16T10:00:01", "app@host", 0.5, 3, "COLLSCAN")));

        List<SlowQuery> result = controller(registry, factory, new QueryMasker(true, false)).slowQueries(7L, 20);

        assertThat(result).extracting(SlowQuery::queryText)
                .containsExactly("SELECT * FROM users WHERE email = ? AND id = ?", "SELECT * FROM orders");
        assertThat(result).extracting(SlowQuery::queryText)
                .allSatisfy(text -> assertThat(text).doesNotContain("leak@example.com").doesNotContain("424242"));
        // 문장 외 필드는 그대로 — 표는 값이 아니라 실행 정보(누가·얼마나·계획)를 그린다
        assertThat(result.get(1).userHost()).isEqualTo("app@host");
        assertThat(result.get(1).lockMs()).isEqualTo(0.5);
        assertThat(result.get(1).rowsSent()).isEqualTo(3);
        assertThat(result.get(1).planSummary()).isEqualTo("COLLSCAN");
    }
}
