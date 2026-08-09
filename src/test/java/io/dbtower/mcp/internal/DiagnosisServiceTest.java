package io.dbtower.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.dbtower.mcp.McpProtocolHandler;
import io.dbtower.analysis.QueryMasker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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

        DiagnosisService svc = new DiagnosisService(handler, ai, true, "mock", new QueryMasker(true, false),
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

        DiagnosisService svc = new DiagnosisService(handler, ai, true, "mock", new QueryMasker(true, false),
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
    void 등록된_읽기_도구는_전부_AI에_노출된다() {
        // 새 읽기 도구를 MCP에 추가하면서 화이트리스트를 갱신하지 않으면 에이전트는 그 도구의
        // 존재조차 모른다 — 요청을 안 하니 거부 로그도, 에러도 없다. 그 조용한 누락을 여기서 잡는다.
        // tools/list는 등록 맵만 읽고 REST로 위임하지 않으므로 목 서버 없이 안전하다.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");

        JsonNode tools = new McpProtocolHandler("http://unused")
                .handle(req).path("result").path("tools");
        assertTrue(tools.isArray() && !tools.isEmpty(), "tools/list가 도구를 돌려줘야 한다");

        Set<String> registered = new TreeSet<>();
        tools.forEach(t -> registered.add(t.path("name").asText()));

        Set<String> unaccounted = new TreeSet<>(registered);
        unaccounted.removeAll(DiagnosisService.READ_ONLY_TOOLS);
        unaccounted.removeAll(DiagnosisService.DELIBERATELY_HIDDEN_TOOLS);
        assertEquals(Set.of(), unaccounted,
                "등록됐지만 AI에 노출되지도, 의도적 제외 목록에도 없는 도구 — 화이트리스트 갱신 누락");

        // 반대 방향 — 도구를 지웠는데 화이트리스트에 이름만 남은 경우
        Set<String> phantom = new TreeSet<>(DiagnosisService.READ_ONLY_TOOLS);
        phantom.removeAll(registered);
        assertEquals(Set.of(), phantom, "화이트리스트에 있으나 MCP에 등록되지 않은 유령 도구");
    }

    @Test
    void 시스템_프롬프트는_대상이_달라도_바이트_동일하다() {
        // 프롬프트 캐싱은 프리픽스 바이트 매칭이라, 대상·시각이 시스템 프롬프트에 섞이면 진단마다
        // 캐시가 깨진다. 실제로 그랬다 — buildSystemPrompt() 끝에 "현재 시각"이 박혀 있어 진단 사이
        // 프리픽스가 매번 달라졌고, 첫 호출 cacheWrite가 19171로 고정이었다(VERIFICATION 120절).
        //
        // 에러가 나지 않는 종류의 회귀라 여기서 계약으로 못박는다: 대상이 무엇이든, 몇 번째 진단이든,
        // AI가 받는 시스템 프롬프트는 같은 문자열이어야 한다. cache_control을 거는 쪽은
        // AiAnalyzerTest가 검증하고, 그 캐시가 걸릴 대상이 안정적인지는 여기가 검증한다.
        List<String> systems = new CopyOnWriteArrayList<>();
        String finalJson = "{\"action\":\"final\",\"answer\":\"근거 부족.\",\"rootCause\":\"미상\","
                + "\"confidence\":\"low\"}";

        // 인스턴스·기종·이름·질문을 전부 다르게 준 두 진단
        for (var target : List.of(new String[]{"1", "POSTGRESQL", "orders-prod", "왜 느려?"},
                new String[]{"2", "MYSQL", "billing-replica", "커넥션이 왜 튀지?"})) {
            DiagnosisService svc = new DiagnosisService(
                    new McpProtocolHandler(baseUrl),
                    (system, user) -> {
                        systems.add(system);
                        return Optional.of(finalJson);
                    },
                    true, "mock", new QueryMasker(true, false), "docs/ai-analysis-rules.md", 5);
            svc.diagnose(Long.parseLong(target[0]), target[1], target[2], target[3]);
        }

        assertEquals(2, systems.size(), "두 진단이 각각 AI를 한 번씩 불렀어야 한다");
        assertEquals(systems.get(0), systems.get(1),
                "시스템 프롬프트가 진단마다 다르다 — 캐시 프리픽스가 깨진다. "
                        + "휘발성 값은 사용자 메시지의 [대상] 블록으로 내려야 한다(VERIFICATION 120절)");

        // 프리픽스를 깨는 값이 실제로 어느 쪽에 실렸는지도 못박는다 — 시스템에 없고 사용자에 있다.
        assertFalse(systems.get(0).contains("orders-prod"), "인스턴스 이름이 시스템 프롬프트에 샜다");
        assertFalse(systems.get(0).contains("instanceId=1"), "instanceId가 시스템 프롬프트에 샜다");

        // 위의 바이트 비교는 같은 실행 안에서 두 번 부르므로 날짜 단위 휘발성을 놓친다 —
        // LocalDate.now()를 넣으면 두 호출이 같은 문자열이라 통과하지만 캐시는 자정마다 깨진다.
        // "날짜꼴 문자열 금지"로는 못 잡는다: 행동 규약에 고정 예시(2026-07-03T15:20:30)가 들어 있어
        // 정상인데도 걸린다. 그래서 오늘 날짜만 금지한다 — 고정 예시는 통과하고 now()만 걸린다.
        assertFalse(systems.get(0).contains(LocalDate.now().toString()),
                "오늘 날짜가 시스템 프롬프트에 있다 — 자정마다 캐시 프리픽스가 깨진다");
    }

    @Test
    void 코드펜스로_감싼_JSON도_파싱한다() {
        McpProtocolHandler handler = new McpProtocolHandler(baseUrl);
        List<String> received = new java.util.ArrayList<>();
        DiagnosisService.AiTurn ai = scripted(received,
                "판단 결과입니다:\n```json\n{\"action\":\"final\",\"answer\":\"근거 부족으로 모른다.\","
                        + "\"rootCause\":\"미상\",\"confidence\":\"low\"}\n```\n이상입니다.");

        DiagnosisService svc = new DiagnosisService(handler, ai, true, "mock", new QueryMasker(true, false),
                "docs/ai-analysis-rules.md", 5);
        DiagnosisService.DiagnosisResult r = svc.diagnose(1, "MYSQL", "db1", "왜?");

        assertEquals("근거 부족으로 모른다.", r.answer());
        assertEquals("low", r.confidence());
    }

    @Test
    void AI_백엔드가_없으면_비활성_결과를_정직하게_돌려준다() {
        McpProtocolHandler handler = new McpProtocolHandler(baseUrl);
        DiagnosisService svc = new DiagnosisService(handler, (s, u) -> Optional.empty(),
                false, "off", new QueryMasker(true, false), "docs/ai-analysis-rules.md", 5);
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

        DiagnosisService svc = new DiagnosisService(handler, ai, true, "mock", new QueryMasker(true, false),
                "docs/ai-analysis-rules.md", 2);
        DiagnosisService.DiagnosisResult r = svc.diagnose(1, "MYSQL", "db1", "왜?");

        assertEquals(2, r.toolCallCount());
        assertEquals("low", r.confidence());
        assertTrue(r.note().contains("최대 스텝"));
        assertTrue(r.answer().contains("확실치 않다"));
    }
}
