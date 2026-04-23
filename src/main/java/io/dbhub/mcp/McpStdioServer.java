package io.dbhub.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DBHub MCP 서버 (확장5) — AI 에이전트가 DBHub를 도구로 쓰게 하는 stdio 어댑터.
 *
 * 웹 UI(확장4)가 사람의 채널이라면 MCP는 AI 에이전트의 채널이다.
 * 회귀 감지(확장3)가 "플랫폼이 사람에게 미는(push)" 알림이라면,
 * MCP는 "에이전트가 필요할 때 당겨쓰는(pull)" 조회다 — 같은 코어를 채널만 바꿔 노출한다.
 *
 * 프레임워크·SDK 없이 프로토콜을 직접 구현했다. MCP stdio는
 * "한 줄 = JSON-RPC 2.0 메시지 하나" 프레이밍이고, 서버가 꼭 알아야 할 메서드는
 * initialize / notifications/initialized / tools/list / tools/call 네 개다.
 * 도구 실행은 전부 기존 REST API 호출로 위임한다 — MCP 계층에 비즈니스 로직이 없어야
 * 채널이 늘어도 검증은 코어 한 곳에서 끝난다.
 *
 * 실행: scripts/dbhub-mcp.sh (MCP 클라이언트가 이 프로세스를 직접 띄운다)
 * 대상 주소: 환경변수 DBHUB_URL (기본 http://localhost:8080)
 */
public final class McpStdioServer {

    private static final String SERVER_NAME = "dbhub";
    private static final String SERVER_VERSION = "0.1.0";
    private static final String DEFAULT_PROTOCOL = "2025-06-18";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    /** 도구 정의 — name -> (설명, 입력 스키마, REST 호출) */
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    private record Tool(String description, ObjectNode inputSchema, ToolCall call) {
    }

    @FunctionalInterface
    private interface ToolCall {
        String invoke(JsonNode args) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        new McpStdioServer(System.getenv().getOrDefault("DBHUB_URL", "http://localhost:8080")).run();
    }

    McpStdioServer(String baseUrl) {
        this.baseUrl = baseUrl;
        registerTools();
    }

    // ---------- 도구 정의 ----------

    private void registerTools() {
        tools.put("list_instances", new Tool(
                "등록된 이기종 DBMS 인스턴스 목록(MySQL/PostgreSQL/SQL Server). id를 다른 도구의 instanceId로 쓴다.",
                schema(Map.of()),
                args -> get("/api/instances")));

        tools.put("health", new Tool(
                "인스턴스 헬스체크 — up 여부, 서버 버전, 응답시간(ms).",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong() + "/health")));

        tools.put("query_stats", new Tool(
                "현재 상위 쿼리 — 시간 점유율(load%), 호출수, 누적 시간, 읽은 행수. 지금 DB를 붙잡고 있는 쿼리를 본다.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"),
                        "limit", intProp("최대 개수 (기본 20)"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong()
                        + "/query-stats?limit=" + optInt(args, "limit", 20))));

        tools.put("slow_queries", new Tool(
                "슬로우 쿼리 목록 — 기종별 소스(performance_schema / pg_stat_statements / DMV)를 통합한 뷰.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"),
                        "limit", intProp("최대 개수 (기본 20)"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong()
                        + "/slow-queries?limit=" + optInt(args, "limit", 20))));

        tools.put("compare", new Tool(
                "시점 비교 — 평소 구간(base) 대비 문제 구간(target)의 쿼리별 QPS·레이턴시·rows/call 증감과 신규 쿼리. "
                        + "장애 원인 쿼리를 찾을 때 가장 먼저 쓰는 도구. 시각은 ISO LocalDateTime (예: 2026-07-03T15:20:30).",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"),
                        "baseFrom", strProp("평소 구간 시작"), "baseTo", strProp("평소 구간 끝"),
                        "targetFrom", strProp("문제 구간 시작"), "targetTo", strProp("문제 구간 끝"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong() + "/compare"
                        + "?baseFrom=" + enc(args.get("baseFrom").asText())
                        + "&baseTo=" + enc(args.get("baseTo").asText())
                        + "&targetFrom=" + enc(args.get("targetFrom").asText())
                        + "&targetTo=" + enc(args.get("targetTo").asText()))));

        tools.put("activity", new Tool(
                "활동 그래프 데이터 — 구간의 배치별 QPS·평균 레이턴시 시계열. 부하 스파이크 시점을 찾을 때 쓴다.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"),
                        "from", strProp("시작 (ISO LocalDateTime)"), "to", strProp("끝"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong() + "/activity"
                        + "?from=" + enc(args.get("from").asText()) + "&to=" + enc(args.get("to").asText()))));

        tools.put("explain", new Tool(
                "실행계획 + 규칙 기반 비효율 지적. SQL의 파라미터 자리(?, $1)는 실제 값으로 치환해서 넘겨야 한다.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"), "sql", strProp("실행계획을 볼 SQL"))),
                args -> post("/api/instances/" + args.get("instanceId").asLong() + "/explain",
                        mapper.createObjectNode().put("sql", args.get("sql").asText()).toString())));

        tools.put("replication", new Tool(
                "복제 상태 통합 뷰 — role, 지연 초, 상세. SHOW REPLICA STATUS / pg_stat_replication / AlwaysOn DMV를 하나의 모델로.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong() + "/replication")));
    }

    // ---------- JSON-RPC 루프 ----------

    void run() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode msg;
            try {
                msg = mapper.readTree(line);
            } catch (Exception e) {
                continue; // 파싱 불가 라인은 무시 (프로토콜상 응답할 id가 없다)
            }
            JsonNode id = msg.get("id");
            String method = msg.path("method").asText("");
            try {
                switch (method) {
                    case "initialize" -> reply(id, initializeResult(msg));
                    case "notifications/initialized", "notifications/cancelled" -> { /* 알림 — 무응답 */ }
                    case "ping" -> reply(id, mapper.createObjectNode());
                    case "tools/list" -> reply(id, toolsListResult());
                    case "tools/call" -> reply(id, toolsCallResult(msg.path("params")));
                    default -> {
                        if (id != null && !id.isNull()) {
                            replyError(id, -32601, "지원하지 않는 메서드: " + method);
                        }
                    }
                }
            } catch (Exception e) {
                if (id != null && !id.isNull()) {
                    replyError(id, -32603, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
        }
    }

    private ObjectNode initializeResult(JsonNode msg) {
        // 클라이언트가 제시한 버전을 그대로 수용 — 우리가 쓰는 부분집합은 버전 간 호환
        String protocol = msg.path("params").path("protocolVersion").asText(DEFAULT_PROTOCOL);
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", protocol);
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode arr = result.putArray("tools");
        tools.forEach((name, tool) -> {
            ObjectNode t = arr.addObject();
            t.put("name", name);
            t.put("description", tool.description());
            t.set("inputSchema", tool.inputSchema());
        });
        return result;
    }

    private ObjectNode toolsCallResult(JsonNode params) {
        String name = params.path("name").asText();
        Tool tool = tools.get(name);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        try {
            if (tool == null) {
                throw new IllegalArgumentException("없는 도구: " + name);
            }
            String body = tool.call().invoke(params.path("arguments"));
            content.addObject().put("type", "text").put("text", body);
            result.put("isError", false);
        } catch (Exception e) {
            content.addObject().put("type", "text")
                    .put("text", "도구 실행 실패: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            result.put("isError", true);
        }
        return result;
    }

    private synchronized void reply(JsonNode id, ObjectNode result) {
        if (id == null || id.isNull()) {
            return;
        }
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        msg.set("result", result);
        System.out.println(msg);
        System.out.flush();
    }

    private synchronized void replyError(JsonNode id, int code, String message) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        ObjectNode err = msg.putObject("error");
        err.put("code", code);
        err.put("message", message);
        System.out.println(msg);
        System.out.flush();
    }

    // ---------- REST 위임 ----------

    private String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30)).GET().build();
        return require2xx(http.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    private String post(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        return require2xx(http.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    private static String require2xx(HttpResponse<String> res) {
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("DBHub API " + res.statusCode() + ": " + res.body());
        }
        return res.body();
    }

    // ---------- 스키마 헬퍼 ----------

    private ObjectNode schema(Map<String, ObjectNode> props) {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        ArrayNode required = s.putArray("required");
        props.forEach((name, prop) -> {
            p.set(name, prop);
            if (!prop.path("description").asText("").contains("기본")) {
                required.add(name); // "기본값" 안내가 있는 파라미터만 선택 입력
            }
        });
        return s;
    }

    private ObjectNode intProp(String description) {
        return mapper.createObjectNode().put("type", "integer").put("description", description);
    }

    private ObjectNode strProp(String description) {
        return mapper.createObjectNode().put("type", "string").put("description", description);
    }

    private static int optInt(JsonNode args, String name, int def) {
        return args.hasNonNull(name) ? args.get(name).asInt(def) : def;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
