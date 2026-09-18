package io.dbtower.mcp.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.DeepAnalyzer;
import io.dbtower.analysis.PlanMasker;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.analysis.RuleBasedAnalyzer;
import io.dbtower.insight.BaselineService;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.PrometheusClient;
import io.dbtower.insight.QuerySnapshotRepository;
import io.dbtower.insight.internal.AiAnalysisRunner;
import io.dbtower.insight.internal.web.InsightController;
import io.dbtower.mcp.McpProtocolHandler;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.operator.model.SlowQuery;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 데이터 보호 사실 고정 (173절 5번) — 화면에 "쿼리의 값은 가려서 보낸다"고 쓰기 전에 코드로 확인한다.
 *
 * <p>진단이 AI에 보내는 transcript에는 도구 결과(observation)가 그대로 들어간다(DiagnosisService).
 * 그러니 값이 새지 않으려면 <b>도구 결과를 만드는 REST 경로</b>에서 리터럴이 가려져야 한다. 이 테스트는
 * 목 REST 서버가 아니라 실제 {@link InsightController}를 태워 그 경로를 그대로 지나간다 — 목으로 결과를
 * 지어내면 "가려진다"는 사실을 검증하는 것이 아니라 검증한 척하는 것이 된다.
 *
 * <p>쿼리 문장을 돌려주는 읽기 도구 셋(세션·상위 쿼리·슬로우 쿼리)이 대표다. 슬로우 쿼리는 1차에서
 * 가리기가 빠진 것을 발견해 이번에 고쳤다(InsightController.slowQueries) — 그 경로도 여기서 함께 못박는다.
 */
class DiagnosisObservationMaskingTest {

    private static final String LEAKY = "SELECT * FROM users WHERE email = 'leak@example.com' AND id = 424242";
    private static final String MASKED = "SELECT * FROM users WHERE email = ? AND id = ?";

    /**
     * PostgreSQL EXPLAIN (FORMAT JSON)의 모양 그대로 — 조건 값이 {@code Filter}에 찍힌다.
     * explain 도구는 설명문이 "파라미터 자리는 실제 값으로 치환해서 넘겨야 한다"고 지시하므로
     * 이 경로에는 가려지지 않은 값이 들어오고, 계획에도 그대로 실려 돌아온다.
     */
    private static final String LEAKY_PLAN = """
            [{"Plan":{"Node Type":"Seq Scan","Relation Name":"users","Plan Rows":1,
            "Total Cost":1834.0,"Filter":"((email)::text = 'leak@example.com'::text)"}}]""";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> hitPaths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startMockRest() throws IOException {
        RegistryService registry = mock(RegistryService.class);
        DbmsOperatorFactory factory = mock(DbmsOperatorFactory.class);
        DatabaseInstance instance = mock(DatabaseInstance.class);
        DbmsOperator operator = mock(DbmsOperator.class);
        when(registry.findById(1L)).thenReturn(instance);
        when(factory.create(instance)).thenReturn(operator);
        when(operator.activeSessions(50)).thenReturn(List.of(
                new SessionInfo(7, "app", "active", "io/table/sql/handler", null, LEAKY, 1200)));
        when(operator.queryStats(20)).thenReturn(List.of(new QueryStat("q1", LEAKY, 10, 100.0, 3)));
        when(operator.slowQueries(20)).thenReturn(List.of(new SlowQuery(LEAKY, 12.0, 5, "2026-09-16T10:00:00")));
        BaselineService baseline = mock(BaselineService.class);
        when(baseline.recentQps(any(), any())).thenReturn(Map.of());

        InsightController controller = new InsightController(registry, factory, mock(ComparisonService.class),
                mock(RuleBasedAnalyzer.class), mock(AiAnalyzer.class), mock(DeepAnalyzer.class),
                mock(QuerySnapshotRepository.class), baseline, mock(PrometheusClient.class),
                new QueryMasker(true, false), mock(AiAnalysisRunner.class),
                mock(io.dbtower.insight.internal.CollectionStatusStore.class));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/api/instances/1/sessions", () -> controller.sessions(1L, 50));
        respond("/api/instances/1/query-stats", () -> controller.queryStats(1L, 20));
        respond("/api/instances/1/slow-queries", () -> controller.slowQueries(1L, 20));
        respond("/api/instances/1/explain", () -> Map.of("plan", LEAKY_PLAN));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, Supplier<Object> body) {
        server.createContext(path, exchange -> {
            hitPaths.add(exchange.getRequestURI().getPath());
            byte[] bytes = mapper.writeValueAsBytes(body.get());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    @Test
    void 세션과_상위쿼리_도구가_돌려준_쿼리_리터럴은_AI에_가지_않는다() {
        List<String> received = new ArrayList<>();
        var queue = new java.util.ArrayDeque<>(List.of(
                "{\"action\":\"call_tool\",\"tool\":\"sessions\",\"arguments\":{\"instanceId\":1},"
                        + "\"reason\":\"누가 막고 있나\"}",
                "{\"action\":\"call_tool\",\"tool\":\"query_stats\",\"arguments\":{\"instanceId\":1},"
                        + "\"reason\":\"누가 시간을 쓰나\"}",
                "{\"action\":\"final\",\"answer\":\"메일 조건 조회가 원인이다.\",\"rootCause\":\"미상\","
                        + "\"confidence\":\"low\"}"));
        DiagnosisService.AiTurn ai = (system, user) -> {
            received.add(user);
            return Optional.ofNullable(queue.poll());
        };
        DiagnosisService svc = new DiagnosisService(new McpProtocolHandler(baseUrl), ai, true, "mock",
                new QueryMasker(true, false), "docs/ai-analysis-rules.md", 5);

        DiagnosisService.DiagnosisResult result = svc.diagnose(1, "MYSQL", "live-mysql", "지금 뭐가 막혀?");

        // 두 도구가 실제 REST 경로를 지났다
        assertThat(result.toolCallCount()).isEqualTo(2);
        assertThat(hitPaths).contains("/api/instances/1/sessions", "/api/instances/1/query-stats");

        // 가려짐이 실제로 일어났다 — 결과가 비어서 통과한 것이 아니다
        assertThat(received).anySatisfy(prompt -> assertThat(prompt).contains(MASKED));

        // 그리고 그 리터럴은 어느 턴의 AI 입력에도 없다
        assertThat(received).isNotEmpty();
        assertThat(received).allSatisfy(prompt -> assertThat(prompt).doesNotContain("leak@example.com"));
    }

    /**
     * 슬로우 쿼리 도구도 같은 규칙 — 이 경로만 가리기가 빠져 있어(1차 보고) 대상 DB의 값이 그대로 나가고 있었다.
     * MySQL slow_log.sql_text에는 사용자가 친 값이 그대로 남는다(PG만 $1로 정규화돼 있다).
     */
    @Test
    void 슬로우쿼리_도구가_돌려준_쿼리_리터럴도_AI에_가지_않는다() {
        List<String> received = new ArrayList<>();
        var queue = new java.util.ArrayDeque<>(List.of(
                "{\"action\":\"call_tool\",\"tool\":\"slow_queries\",\"arguments\":{\"instanceId\":1},"
                        + "\"reason\":\"느린 쿼리를 본다\"}",
                "{\"action\":\"final\",\"answer\":\"메일 조건 조회가 느리다.\",\"rootCause\":\"미상\","
                        + "\"confidence\":\"low\"}"));
        DiagnosisService.AiTurn ai = (system, user) -> {
            received.add(user);
            return Optional.ofNullable(queue.poll());
        };
        DiagnosisService svc = new DiagnosisService(new McpProtocolHandler(baseUrl), ai, true, "mock",
                new QueryMasker(true, false), "docs/ai-analysis-rules.md", 5);

        DiagnosisService.DiagnosisResult result = svc.diagnose(1, "MYSQL", "live-mysql", "왜 느려?");

        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(hitPaths).contains("/api/instances/1/slow-queries");
        assertThat(received).anySatisfy(prompt -> assertThat(prompt).contains(MASKED));
        assertThat(received).allSatisfy(prompt -> assertThat(prompt).doesNotContain("leak@example.com"));
    }

    /**
     * MCP 진단 경로 — explain 도구가 돌려준 <b>실행계획</b>에도 값이 실려 나간다.
     *
     * <p>{@link QueryMasker}는 SQL 문장을 가리지만 옵티마이저가 계획 안에 찍어 둔 조건 값은 다른 문자열이다.
     * 계획 가림({@code dbtower.masking.mask-ai-plan})을 켜면 이 경로도 함께 막혀야 한다.
     */
    @Test
    void explain_도구가_돌려준_실행계획의_값도_AI에_가지_않는다() {
        List<String> received = new ArrayList<>();
        var queue = new java.util.ArrayDeque<>(List.of(
                "{\"action\":\"call_tool\",\"tool\":\"explain\",\"arguments\":{\"instanceId\":1,"
                        + "\"sql\":\"SELECT * FROM users WHERE email = 'leak@example.com'\"},"
                        + "\"reason\":\"계획을 본다\"}",
                "{\"action\":\"final\",\"answer\":\"풀스캔이다.\",\"rootCause\":\"미상\","
                        + "\"confidence\":\"low\"}"));
        DiagnosisService.AiTurn ai = (system, user) -> {
            received.add(user);
            return Optional.ofNullable(queue.poll());
        };
        DiagnosisService svc = new DiagnosisService(new McpProtocolHandler(baseUrl), ai, true, "mock",
                new QueryMasker(true, false), "docs/ai-analysis-rules.md", 5,
                () -> DiagnosisGuard.CallerScope.GLOBAL, (action, instanceId, outcome) -> { },
                new PlanMasker(true, true), id -> DbmsType.POSTGRESQL);

        DiagnosisService.DiagnosisResult result = svc.diagnose(1, "POSTGRESQL", "live-pg", "왜 느려?");

        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(hitPaths).contains("/api/instances/1/explain");
        // 계획이 실제로 프롬프트에 붙었다 — 결과가 비어서 통과한 것이 아니다
        assertThat(received).anySatisfy(prompt -> assertThat(prompt).contains("Seq Scan"));
        // 진단 재료인 행수·비용은 남는다
        assertThat(received).anySatisfy(prompt -> assertThat(prompt).contains("1834.0"));
        assertThat(received).allSatisfy(prompt -> assertThat(prompt).doesNotContain("leak@example.com"));
    }

    /**
     * 같은 경로, 계획 가림이 꺼진 기본 설정 — 값이 그대로 나간다는 사실을 못박는다.
     * 이 테스트가 깨지면 기본값이 바뀐 것이므로 문서(README·검증 기록)도 같이 고쳐야 한다.
     */
    @Test
    void 계획_가림이_꺼져_있으면_explain_결과는_원문으로_나간다() {
        List<String> received = new ArrayList<>();
        var queue = new java.util.ArrayDeque<>(List.of(
                "{\"action\":\"call_tool\",\"tool\":\"explain\",\"arguments\":{\"instanceId\":1,"
                        + "\"sql\":\"SELECT * FROM users WHERE email = 'leak@example.com'\"},"
                        + "\"reason\":\"계획을 본다\"}",
                "{\"action\":\"final\",\"answer\":\"풀스캔이다.\",\"rootCause\":\"미상\","
                        + "\"confidence\":\"low\"}"));
        DiagnosisService.AiTurn ai = (system, user) -> {
            received.add(user);
            return Optional.ofNullable(queue.poll());
        };
        DiagnosisService svc = new DiagnosisService(new McpProtocolHandler(baseUrl), ai, true, "mock",
                new QueryMasker(true, false), "docs/ai-analysis-rules.md", 5);

        svc.diagnose(1, "POSTGRESQL", "live-pg", "왜 느려?");

        assertThat(received).anySatisfy(prompt -> assertThat(prompt).contains("leak@example.com"));
    }
}
