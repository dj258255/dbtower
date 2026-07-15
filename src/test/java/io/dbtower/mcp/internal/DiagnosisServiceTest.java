package io.dbtower.mcp.internal;

import com.sun.net.httpserver.HttpServer;
import io.dbtower.mcp.McpProtocolHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D3 도구 사용 루프 오케스트레이션 검증 (AI 백엔드 없이 결정론적으로).
 *
 * 스크립트된 AI(다음 도구 호출 JSON을 미리 정해둠)와 "실제" McpProtocolHandler를 쓰되,
 * 핸들러의 REST 위임 대상은 JDK 내장 HttpServer로 띄운 목 서버로 돌린다. 그래서
 * "AI가 도구를 정함 → 핸들러가 tools/call로 실제 실행 → REST 응답 → 결과를 다시 AI에 반영 →
 * 다음 도구 → 최종 종합"의 전체 사슬이 실측 없이도 그대로 돈다.
 */
class DiagnosisServiceTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> hitPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startMockRest() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // compare — 평소 대비 급증한 신규 쿼리 하나
        context("/api/instances/1/compare",
                "{\"surged\":[{\"queryText\":\"SELECT * FROM orders WHERE memo LIKE '%foo%'\","
                        + "\"qpsBase\":1,\"qpsTarget\":120,\"new\":true}]}");
        // explain — 그 쿼리의 실행계획(풀스캔 신호)
        context("/api/instances/1/explain",
                "{\"plan\":\"Seq Scan on orders (cost=0..99999 rows=2000000)\","
                        + "\"findings\":[\"Seq Scan — 인덱스 부재로 테이블 풀스캔\"]}");
        // wait_events — 그 시간 IO 대기가 지배적
        context("/api/instances/1/wait-events",
                "[{\"event\":\"io/table/sql/handler\",\"waits\":98000,\"category\":\"IO\"}]");

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void context(String path, String json) {
        server.createContext(path, ex -> {
            hitPaths.add(ex.getRequestURI().getPath());
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    /** 미리 정해둔 순서대로 AI 결정 JSON을 돌려주는 스크립트 AI. 매 턴의 입력(누적 대화)도 기록한다. */
    private DiagnosisService.AiTurn scripted(List<String> received, String... responses) {
        Deque<String> queue = new ArrayDeque<>(List.of(responses));
        return (system, user) -> {
            received.add(user);
            return Optional.ofNullable(queue.poll());
        };
    }

    @Test
    void AI가_도구_3개를_연쇄_호출하고_근본원인을_종합한다() {
        McpProtocolHandler handler = new McpProtocolHandler(baseUrl); // 목 REST로 위임
        List<String> received = new java.util.ArrayList<>();
        DiagnosisService.AiTurn ai = scripted(received,
                "{\"action\":\"call_tool\",\"tool\":\"compare\",\"arguments\":{\"instanceId\":1,"
                        + "\"baseFrom\":\"2026-07-03T10:00:00\",\"baseTo\":\"2026-07-03T10:10:00\","
                        + "\"targetFrom\":\"2026-07-03T14:00:00\",\"targetTo\":\"2026-07-03T14:10:00\"},"
                        + "\"reason\":\"급증·신규 쿼리부터 찾는다\"}",
                "{\"action\":\"call_tool\",\"tool\":\"explain\",\"arguments\":{\"instanceId\":1,"
                        + "\"sql\":\"SELECT * FROM orders WHERE memo LIKE '%foo%'\"},"
                        + "\"reason\":\"급증 쿼리의 실행계획을 본다\"}",
                "{\"action\":\"call_tool\",\"tool\":\"wait_events\",\"arguments\":{\"instanceId\":1},"
                        + "\"reason\":\"병목이 IO인지 확인한다\"}",
                "{\"action\":\"final\",\"answer\":\"신규 LIKE '%foo%' 풀스캔이 IO 대기를 유발해 느려졌다.\","
                        + "\"rootCause\":\"인덱스 없는 후위 와일드카드 LIKE 풀스캔\",\"confidence\":\"high\"}");

        DiagnosisService svc = new DiagnosisService(handler, ai, true, "mock",
                "docs/ai-analysis-rules.md", 5);
        DiagnosisService.DiagnosisResult r = svc.diagnose(1, "POSTGRESQL", "orders-prod",
                "어제 오후에 왜 느려졌어?");

        // 도구 2개 이상을 실제로 엮었다
        assertEquals(3, r.toolCallCount(), "compare·explain·wait_events 3개 실행");
        assertEquals(List.of("compare", "explain", "wait_events"),
                r.toolCalls().stream().map(DiagnosisService.ToolCallTrace::tool).toList());
        assertTrue(r.toolCalls().stream().noneMatch(DiagnosisService.ToolCallTrace::rejected));
        // 실제 REST가 3번 맞았다(핸들러가 진짜로 위임함)
        assertTrue(hitPaths.stream().anyMatch(p -> p.endsWith("/compare")));
        assertTrue(hitPaths.stream().anyMatch(p -> p.endsWith("/explain")));
        assertTrue(hitPaths.stream().anyMatch(p -> p.endsWith("/wait-events")));
        // 근본원인 종합 + 투명성 필드
        assertTrue(r.answer().contains("풀스캔"));
        assertEquals("high", r.confidence());
        assertTrue(r.aiEnabled());
        // 도구 결과가 다음 AI 턴 입력에 반영됐다(explain 턴은 compare 결과를 이미 봤다)
        assertTrue(received.get(1).contains("orders"), "compare 결과가 다음 프롬프트에 실림");
        assertTrue(received.get(2).contains("Seq Scan"), "explain 결과가 다음 프롬프트에 실림");
    }

    @Test
    void 쓰기_도구를_요청하면_실행하지_않고_거부한다() {
        McpProtocolHandler handler = new McpProtocolHandler(baseUrl);
        List<String> received = new java.util.ArrayList<>();
        DiagnosisService.AiTurn ai = scripted(received,
                // 화이트리스트 밖(가상의 파괴적 도구) — 실행돼선 안 된다
                "{\"action\":\"call_tool\",\"tool\":\"kill_session\",\"arguments\":{\"instanceId\":1,\"pid\":42},"
                        + "\"reason\":\"막는 세션을 끊자\"}",
                "{\"action\":\"final\",\"answer\":\"세션 종료는 할 수 없어 조회 결과만으로 답한다.\","
                        + "\"rootCause\":\"미상\",\"confidence\":\"low\"}");

        DiagnosisService svc = new DiagnosisService(handler, ai, true, "mock",
                "docs/ai-analysis-rules.md", 5);
        DiagnosisService.DiagnosisResult r = svc.diagnose(1, "MYSQL", "db1", "느린 세션 죽여줘");

        assertEquals(0, r.toolCallCount(), "실행된 도구 없음");
        assertTrue(r.toolCalls().get(0).rejected(), "kill_session은 거부 표시");
        assertEquals("kill_session", r.toolCalls().get(0).tool());
        // 목 REST에는 그 어떤 파괴적 호출도 도달하지 않았다
        assertTrue(hitPaths.isEmpty(), "화이트리스트 밖 도구는 REST로 나가지 않는다");
        assertFalse(DiagnosisService.READ_ONLY_TOOLS.contains("kill_session"));
    }

    @Test
    void 코드펜스로_감싼_JSON도_파싱한다() {
        McpProtocolHandler handler = new McpProtocolHandler(baseUrl);
        List<String> received = new java.util.ArrayList<>();
        DiagnosisService.AiTurn ai = scripted(received,
                "판단 결과입니다:\n```json\n{\"action\":\"final\",\"answer\":\"근거 부족으로 모른다.\","
                        + "\"rootCause\":\"미상\",\"confidence\":\"low\"}\n```\n이상입니다.");

        DiagnosisService svc = new DiagnosisService(handler, ai, true, "mock",
                "docs/ai-analysis-rules.md", 5);
        DiagnosisService.DiagnosisResult r = svc.diagnose(1, "MYSQL", "db1", "왜?");

        assertEquals("근거 부족으로 모른다.", r.answer());
        assertEquals("low", r.confidence());
    }

    @Test
    void AI_백엔드가_없으면_비활성_결과를_정직하게_돌려준다() {
        McpProtocolHandler handler = new McpProtocolHandler(baseUrl);
        DiagnosisService svc = new DiagnosisService(handler, (s, u) -> Optional.empty(),
                false, "off", "docs/ai-analysis-rules.md", 5);
        DiagnosisService.DiagnosisResult r = svc.diagnose(1, "MYSQL", "db1", "왜 느려?");

        assertFalse(r.aiEnabled());
        assertNull(r.answer());
        assertTrue(r.note().contains("비활성"));
        assertTrue(hitPaths.isEmpty(), "비활성이면 도구를 부르지 않는다");
    }

    @Test
    void 최대_스텝에_도달하면_종합을_강제한다() {
        McpProtocolHandler handler = new McpProtocolHandler(baseUrl);
        List<String> received = new java.util.ArrayList<>();
        // 계속 compare만 부르는 AI — 2스텝 소진 후 강제 종합에서 final을 낸다
        DiagnosisService.AiTurn ai = scripted(received,
                "{\"action\":\"call_tool\",\"tool\":\"compare\",\"arguments\":{\"instanceId\":1,"
                        + "\"baseFrom\":\"2026-07-03T10:00:00\",\"baseTo\":\"2026-07-03T10:10:00\","
                        + "\"targetFrom\":\"2026-07-03T14:00:00\",\"targetTo\":\"2026-07-03T14:10:00\"},\"reason\":\"a\"}",
                "{\"action\":\"call_tool\",\"tool\":\"compare\",\"arguments\":{\"instanceId\":1,"
                        + "\"baseFrom\":\"2026-07-03T10:00:00\",\"baseTo\":\"2026-07-03T10:10:00\","
                        + "\"targetFrom\":\"2026-07-03T14:00:00\",\"targetTo\":\"2026-07-03T14:10:00\"},\"reason\":\"b\"}",
                // 강제 종합 턴
                "{\"action\":\"final\",\"answer\":\"근거가 부분적이라 확실치 않다.\",\"rootCause\":\"미상\",\"confidence\":\"low\"}");

        DiagnosisService svc = new DiagnosisService(handler, ai, true, "mock",
                "docs/ai-analysis-rules.md", 2);
        DiagnosisService.DiagnosisResult r = svc.diagnose(1, "MYSQL", "db1", "왜?");

        assertEquals(2, r.toolCallCount());
        assertEquals("low", r.confidence());
        assertTrue(r.note().contains("최대 스텝"));
        assertTrue(r.answer().contains("확실치 않다"));
    }
}
