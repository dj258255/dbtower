package io.dbtower.aiops.internal;

import io.dbtower.advisor.AdvisorService;
import io.dbtower.aiops.AiOperationTrigger;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.finops.FinOpsQuery;
import io.dbtower.insight.BaselineService;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.ComparisonService.CompareResult;
import io.dbtower.insight.ComparisonService.WindowSummary;
import io.dbtower.insight.QueryDiff;
import io.dbtower.insight.WaitEventHistoryService;
import io.dbtower.insight.WaitEventHistoryService.WaitPoint;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.score.HealthScoreView;
import io.dbtower.score.ScoreQuery;
import io.dbtower.slo.SloService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 169절 실측에서 드러난 두 사실 표기 — 시간을 재지 않은 대기 이벤트, 이전 구간이 빈 쿼리 비교. */
class FactCollectorTest {

    private final ScoreQuery score = mock(ScoreQuery.class);
    private final ComparisonService comparison = mock(ComparisonService.class);
    private final WaitEventHistoryService waits = mock(WaitEventHistoryService.class);
    private final FactCollector collector = new FactCollector(score, comparison, waits, mock(BaselineService.class),
            mock(BackupFreshnessService.class), mock(SloService.class), mock(AdvisorService.class), mock(FinOpsQuery.class));
    private final DatabaseInstance instance = instance();

    private static DatabaseInstance instance() {
        DatabaseInstance i = mock(DatabaseInstance.class);
        when(i.getId()).thenReturn(2L);
        when(i.getName()).thenReturn("mysql-a");
        when(i.getType()).thenReturn(DbmsType.MYSQL);
        return i;
    }

    @Test
    void 횟수만_있고_시간이_0인_대기는_0ms가_아니라_시간_기록_없음이다() {
        when(score.scoreFor(anyLong())).thenReturn(new HealthScoreView(2L, 90, "A", false));
        when(waits.inWindow(anyLong(), any(), any(), anyInt())).thenReturn(List.of(
                new WaitPoint("io", "socket/client_connection", 239710, 0.0),
                new WaitPoint("io", "innodb_data_file", 5866, 749.5)));
        when(comparison.compare(anyLong(), any(), any(), any(), any())).thenThrow(new IllegalArgumentException("없음"));

        var collected = collector.collect(AiOperationType.QUERY_DIAGNOSIS, List.of(instance),
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now());

        assertThat(collected.facts()).anyMatch(f -> f.contains("239710회, 시간 기록 없음(횟수만 집계됨)"))
                .anyMatch(f -> f.contains("5866회, 누적 749.5ms"))
                .noneMatch(f -> f.contains("0.0ms"));
    }

    @Test
    void 이전_구간이_비면_분석_구간_상위_쿼리를_주되_회귀_판정은_만들지_않는다() {
        when(score.scoreFor(anyLong())).thenReturn(new HealthScoreView(2L, 90, "A", false));
        when(waits.inWindow(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusHours(1);
        WindowSummary w = new WindowSummary(500, 6000.0, 12.0, 900, 2);
        QueryDiff q = new QueryDiff("d1", "SELECT * FROM t WHERE id = 7", 0.1, 0.1, 0.0, 12.0, 12.0, 0.0,
                3.0, 3.0, 0.0, false);
        // 이전 구간과의 비교는 실패하고, 같은 구간끼리(기준 = 분석 구간)는 성공한다
        when(comparison.compare(anyLong(), any(), any(), any(), any())).thenAnswer(inv -> {
            if (inv.getArgument(1).equals(inv.getArgument(3)) && inv.getArgument(2).equals(inv.getArgument(4))) {
                return new CompareResult(w, w, 0.0, 0.0, 0.0, 0, List.of(q));
            }
            throw new IllegalArgumentException("구간 안에 스냅샷 배치가 2개 이상 필요합니다 (현재 0개)");
        });

        var collected = collector.collect(AiOperationType.REGRESSION_EXPLANATION, List.of(instance), from, to);

        assertThat(collected.facts()).anyMatch(f -> f.contains("분석 구간 호출 500회, 평균 12.0ms (이전 구간 비교 없음)"))
                .anyMatch(f -> f.contains("쿼리 d1: 평균 12.0ms") && !f.contains("id = 7"));
        assertThat(collected.uncertainties()).anyMatch(u -> u.contains("변화율을 계산하지 못했습니다"));
        assertThat(collected.ruleFindings()).noneMatch(r -> r.contains("증가"));
    }

    @Test
    void 작은_수치는_0으로_뭉개지지_않고_새_쿼리는_이전_값을_적지_않으며_시스템_조회는_뒤로_간다() {
        assertThat(FactCollector.num(0.04)).isEqualTo("0.04");
        assertThat(FactCollector.num(0.0)).isEqualTo("0.0");
        assertThat(FactCollector.num(12.345)).isEqualTo("12.3");

        when(score.scoreFor(anyLong())).thenReturn(new HealthScoreView(2L, 90, "A", false));
        when(waits.inWindow(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        WindowSummary w = new WindowSummary(500, 6000.0, 12.0, 900, 3);
        QueryDiff catalog = new QueryDiff("c1", "SHOW GLOBAL VARIABLES LIKE ?", 5.0, 5.0, 0.0, 20.0, 20.0, 0.0,
                1.0, 1.0, 0.0, false);
        QueryDiff app = new QueryDiff("a1", "SELECT * FROM orders WHERE id = 3", 0.0, 0.04, null, 0.0, 1.9, null,
                0.0, 41.0, null, true);
        when(comparison.compare(anyLong(), any(), any(), any(), any()))
                .thenReturn(new CompareResult(w, w, 0.0, 0.0, 0.0, 2, List.of(catalog, app)));

        var collected = collector.collect(AiOperationType.QUERY_DIAGNOSIS, List.of(instance),
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now());
        var queries = collected.facts().stream().filter(f -> f.contains(" 쿼리 ")).toList();

        assertThat(queries.get(0)).contains("쿼리 a1 (새 쿼리): 평균 1.9ms, 초당 0.04, 호출당 행 41.0").doesNotContain("->");
        assertThat(queries.get(1)).contains("쿼리 c1 [시스템·세션 조회]");
        assertThat(collected.ruleFindings()).anyMatch(r -> r.contains("2개 나타났습니다(사실에는 부하 상위 5개만 싣습니다)"));
        assertThat(FactCollector.catalog(new QueryDiff("s", "SET autocommit = ?", 0, 0, null, 0, 0, null, 0, 0, null, false)))
                .isTrue();
        assertThat(FactCollector.catalog(new QueryDiff("s", "SELECT @@session.transaction_isolation", 0, 0, null, 0, 0, null,
                0, 0, null, false))).isTrue();
    }

    @Test
    void 경보가_만든_회귀_작업은_감지기와_같은_창으로_다시_비교한_사실을_싣고_사람이_올린_작업에는_싣지_않는다() {
        when(score.scoreFor(anyLong())).thenReturn(new HealthScoreView(2L, 49, "F", false));
        when(waits.inWindow(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        OffsetDateTime to = OffsetDateTime.parse("2026-09-16T06:34:49Z");
        OffsetDateTime from = to.minusMinutes(20); // AlertTriggerListener가 넘기는 창 = 최근 5 + 직전 15
        WindowSummary w = new WindowSummary(815, 1304.0, 1.6, 90000, 12);
        // 170절 주입 회귀 — 조인 키에 인덱스 없는 자기조인. 경보는 이 쿼리를 QPS 0.35, rows/call 4000으로 보고했다
        QueryDiff injected = new QueryDiff("4be7af9b", "SELECT COUNT(*) FROM sample.orders o1 JOIN sample.orders o2 ON o1.amount = o2.amount",
                0.0, 0.35, null, 0.0, 1.2, null, 0.0, 4000.0, null, true);
        // 감지기 창(직전 15분 -> 최근 5분)으로 불렸을 때만 그 쿼리를 준다. 20분 창 비교에서는 같은 쿼리가 초당 0.1로 희석된다
        when(comparison.compare(anyLong(), any(), any(), any(), any())).thenAnswer(inv -> {
            LocalDateTime baseFrom = inv.getArgument(1), baseTo = inv.getArgument(2);
            LocalDateTime targetFrom = inv.getArgument(3), targetTo = inv.getArgument(4);
            boolean detectorWindow = Duration.between(baseFrom, baseTo).toMinutes() == 15
                    && Duration.between(targetFrom, targetTo).toMinutes() == 5 && baseTo.equals(targetFrom);
            QueryDiff diluted = new QueryDiff("4be7af9b", injected.queryText(), 0.0, 0.1, null, 0.0, 1.2, null, 0.0, 4000.0, null, true);
            return new CompareResult(w, w, 0.0, 20.8, 0.0, 1, List.of(detectorWindow ? injected : diluted));
        });

        var fromAlert = collector.collect(AiOperationType.REGRESSION_EXPLANATION, AiOperationTrigger.ALERT, List.of(instance), from, to);

        assertThat(fromAlert.facts())
                .anyMatch(f -> f.contains("[경보 기준: 최근 5분 vs 직전 15분]") && f.contains("쿼리 4be7af9b (새 쿼리)")
                        && f.contains("초당 0.35") && f.contains("호출당 행 4000.0"))
                // 원래 창의 사실도 그대로 남는다 — 경보 창이 대신하는 것이 아니라 덧붙는다
                .anyMatch(f -> f.startsWith("[mysql-a] 쿼리 4be7af9b") && f.contains("초당 0.1"));
        // 판정은 경보가 이미 냈다 — 창이 다른 판정을 하나 더 세우지 않는다
        assertThat(fromAlert.ruleFindings()).noneMatch(r -> r.contains("경보 기준"));

        var fromPerson = collector.collect(AiOperationType.REGRESSION_EXPLANATION, AiOperationTrigger.WEB, List.of(instance), from, to);
        assertThat(fromPerson.facts()).noneMatch(f -> f.contains("경보 기준"));
    }
}
