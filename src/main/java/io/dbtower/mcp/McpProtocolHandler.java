package io.dbtower.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * MCP JSON-RPC 처리 코어 — 전송 계층과 분리되어 stdio(McpStdioServer)와
 * HTTP(McpHttpController) 양쪽에서 같은 도구·같은 로직을 쓴다.
 *
 * 도구 실행은 전부 DBTower REST API 위임 — MCP 계층에 비즈니스 로직이 없어야
 * 채널이 늘어도 검증·보안은 코어 한 곳에서 끝난다.
 */
public final class McpProtocolHandler {

    private static final String SERVER_NAME = "dbtower";
    private static final String SERVER_VERSION = "0.1.0";
    private static final String DEFAULT_PROTOCOL = "2025-06-18";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String apiToken; // REST 위임 호출용 서비스 토큰 (null이면 헤더 생략 — 테스트 등)
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    private record Tool(String description, ObjectNode inputSchema, ToolCall call) {
    }

    @FunctionalInterface
    private interface ToolCall {
        String invoke(JsonNode args) throws Exception;
    }

    public McpProtocolHandler(String baseUrl) {
        this(baseUrl, null);
    }

    public McpProtocolHandler(String baseUrl, String apiToken) {
        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
        registerTools();
    }

    /**
     * JSON-RPC 메시지 하나를 처리한다.
     * 응답이 필요 없는 알림(notification)이면 null을 돌려준다 — 전송 계층이 무응답/202로 처리.
     */
    public ObjectNode handle(JsonNode msg) {
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText("");
        boolean isNotification = (id == null || id.isNull());
        try {
            return switch (method) {
                case "initialize" -> result(id, initializeResult(msg));
                case "notifications/initialized", "notifications/cancelled" -> null;
                case "ping" -> result(id, mapper.createObjectNode());
                case "tools/list" -> result(id, toolsListResult());
                case "tools/call" -> result(id, toolsCallResult(msg.path("params")));
                default -> isNotification ? null : error(id, -32601, "지원하지 않는 메서드: " + method);
            };
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return isNotification ? null : error(id, -32603, message);
        }
    }

    // ---------- 도구 정의 ----------

    private void registerTools() {
        tools.put("list_instances", new Tool(
                "등록된 이기종 DBMS 인스턴스 목록(MySQL/PostgreSQL/SQL Server/Oracle/MongoDB). id를 다른 도구의 instanceId로 쓴다.",
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
                "슬로우 쿼리 목록 — 기종별 소스(performance_schema / pg_stat_statements / DMV / V$SQL / system.profile)를 통합한 뷰.",
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

        // 심화 아크 5 — "그 시각 CPU가 실제로 높았나"를 AI가 스스로 확인하는 증거 수집 도구
        tools.put("metrics", new Tool(
                "호스트 CPU%·DB Connections 시계열(Prometheus exporter) — 부하 원인 진단 시 시스템 자원 관점의 증거. "
                        + "미수집이면 사유가 note로 온다(지어내지 않음).",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"),
                        "from", strProp("시작 (ISO LocalDateTime)"), "to", strProp("끝"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong() + "/metrics"
                        + "?from=" + enc(args.get("from").asText()) + "&to=" + enc(args.get("to").asText()))));

        tools.put("explain", new Tool(
                "실행계획 + 규칙 기반 비효율 지적. SQL의 파라미터 자리(?, $1)는 실제 값으로 치환해서 넘겨야 한다.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"), "sql", strProp("실행계획을 볼 SQL"))),
                args -> post("/api/instances/" + args.get("instanceId").asLong() + "/explain",
                        mapper.createObjectNode().put("sql", args.get("sql").asText()).toString())));

        tools.put("wait_events", new Tool(
                "상위 대기 이벤트 — 그 시간에 무엇을 기다렸나(CPU/IO/Lock). MySQL/MSSQL/Oracle은 기동 이후 누적, "
                        + "PostgreSQL은 현재 활성 세션 스냅샷, MongoDB는 대기 큐/티켓 게이지. query_stats(누가 시간을 쓰나)와 함께 본다.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"),
                        "limit", intProp("최대 개수 (기본 20)"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong()
                        + "/wait-events?limit=" + optInt(args, "limit", 20))));

        tools.put("replication", new Tool(
                "복제 상태 통합 뷰 — role, 지연 초, 상세. SHOW REPLICA STATUS / pg_stat_replication / AlwaysOn DMV를 하나의 모델로.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong() + "/replication")));

        // 세션 조회는 읽기라 에이전트에게 노출한다. 그러나 세션 종료(kill)는 의도적으로 MCP 도구로
        // 만들지 않는다 — 에이전트가 스스로 운영 세션을 끊는 것은 위험이 너무 크다. kill은 사람이
        // 웹 콘솔에서 ADMIN 권한으로만 실행한다(REST POST /sessions/{pid}/kill).
        tools.put("sessions", new Tool(
                "현재 활성 세션과 블로킹 관계 — pid, user, state, 대기 이벤트, blockedByPid(누가 나를 막나), 쿼리, 경과(ms). "
                        + "장애 시 '지금 누가 누구를 막고 있나'를 본다. (세션 종료는 위험이 커서 MCP로 노출하지 않는다 — 웹 콘솔 ADMIN 전용)",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"),
                        "limit", intProp("최대 개수 (기본 50)"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong()
                        + "/sessions?limit=" + optInt(args, "limit", 50))));

        tools.put("schema", new Tool(
                "인스턴스 스키마 구조 요약 (B7) — 테이블·컬럼(타입·nullable)·인덱스(컬럼·유니크). 완벽한 DDL이 아니라 "
                        + "비교에 필요한 구조만. MongoDB는 스키마리스라 컬렉션·인덱스 구조만.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong() + "/schema")));

        tools.put("partitions", new Tool(
                "테이블 파티션 목록 (D5) — 테이블·파티션명·방식(RANGE/LIST/HASH)·경계·행수·크기. "
                        + "MySQL/PostgreSQL/Oracle/SQL Server는 각 카탈로그를 통합한 뷰, MongoDB는 파티션 개념이 없어 UNSUPPORTED. "
                        + "파티션 없는 테이블/DB는 빈 결과. 조회 전용 — 파티션 생성·삭제는 하지 않는다.",
                schema(Map.of("instanceId", intProp("대상 인스턴스 id"),
                        "limit", intProp("최대 개수 (기본 50)"))),
                args -> get("/api/instances/" + args.get("instanceId").asLong()
                        + "/partitions?limit=" + optInt(args, "limit", 50))));

        tools.put("schema_diff", new Tool(
                "두 인스턴스 스키마 비교 (B7) — 같은 역할의 두 장비(스테이징 vs 운영)에서 '왜 저 장비만 다르지'를 추적한다. "
                        + "추가(right에만)/삭제(left에만)/변경된 테이블·컬럼·인덱스. 기종이 다르면 타입 표기 차이 경고 포함.",
                schema(Map.of("left", intProp("기준(베이스라인) 인스턴스 id"),
                        "right", intProp("비교 대상 인스턴스 id"))),
                args -> get("/api/schema-diff?left=" + args.get("left").asLong()
                        + "&right=" + args.get("right").asLong())));

        // 온라인 스키마 변경(gh-ost, B4)은 의도적으로 MCP 도구로 노출하지 않는다.
        // 실제 테이블 구조를 바꾸는 파괴적 행위를 에이전트가 스스로 실행하는 건 위험하기 때문 —
        // 사람(ADMIN)이 웹 콘솔에서 dry-run으로 먼저 확인하고 실행하도록 REST로만 연다.

        // ---------- 15단계: 장기 마트 (lakehouse — Metabase 경유, SELECT 전용) ----------
        // 3계층 분업: 위 도구들 = 라이브 7일 시야, 아래 둘 = 장기(주~분기, DuckLake 마트).
        // 관제 대상 DB 직결 질의는 어느 도구에도 없다(원천 무부하 원칙).
        tools.put("lakehouse_query", new Tool(
                "장기 마트 SELECT 질의(수개월 이력 — 7일 시야 도구들이 못 답하는 추세·분기 비교). "
                        + "실재 테이블: "
                        + "mart_query_regression(instance_id, query_id, recent/prior avg_latency_ms, "
                        + "latency_increase_ms, increase_pct — 최근7일 vs 직전30일 악화 랭킹), "
                        + "fct_query_daily(instance_id, query_id, dt, delta_calls, delta_total_time_ms, avg_latency_ms), "
                        + "fct_query_hourly(+hour), fct_size_daily(instance_id, object_name, dt, total_bytes), "
                        + "mart_capacity_forecast(instance_id, days_observed, learning, current_bytes, "
                        + "slope_bytes_per_day, days_to_threshold, risk_flag — 용량 D-day), "
                        + "mart_baseline_longterm(dow, hour, mean_delta_calls — 요일x시간대 장기 베이스라인), "
                        + "mart_wait_top(instance_id, rank_in_instance, event_name, category, total_delta_ms, "
                        + "sum_last_ms — 인스턴스별 상위 대기), "
                        + "fct_wait_event_daily(instance_id, event_name, dt, delta_ms, delta_count, last_ms), "
                        + "fct_backup_daily(instance_id, dt, total_runs, success_runs, failed_runs, verified_runs, "
                        + "last_started_at), "
                        + "mart_backup_rpo(instance_id, as_of_dt, last_success_dt, gap_days, no_successful_backup, "
                        + "rpo_status — ok/breach/no_backup_observed, 백업 공백), "
                        + "fct_plan_change_daily(instance_id, dt, plan_changes, queries_flipped), "
                        + "mart_plan_regression(instance_id, query_id, flip_dt, prev/new_plan_hash, before/after_avg_ms, "
                        + "latency_ratio, after_calls, verdict — REGRESSED/IMPROVED/NEUTRAL/PENDING/AMBIGUOUS, 플랜 회귀), "
                        + "mart_index_verdict(instance_id, table_name, index_name, total_scans, last_used_dt, "
                        + "observation_days, is_unique, size_bytes, verdict — in_use/constraint_backed/"
                        + "insufficient_observation/candidate_unused, 미사용 인덱스), "
                        + "fct_index_daily(instance_id, table_name, index_name, dt, delta_scans), "
                        + "mart_weekly_ops_report(week_start, is_partial_week, instance_id, capacity_worst_risk, "
                        + "min_days_to_threshold, top_wait_event, plan_flips_this_week, plan_regressed_this_week, "
                        + "backup_gap_days, backup_status — 인스턴스별 주간 요약 한 행), "
                        + "mart_config_change(instance_id, dt, param_name, old_value, new_value, change_kind — "
                        + "설정 변경 타임라인, 누가는 없음), "
                        + "mart_config_impact(instance_id, change_dt, change_source, change_key, plan_flips_after, "
                        + "regressed_after, before/after_latency_ms, latency_ratio, correlation — followed_by_"
                        + "regression/plan_flip/latency_rise/no_signal, 변경 뒤 회귀 상관·상관≠인과), "
                        + "pipeline_run_log(파이프라인 런 메타). 이 목록에 없는 컬럼은 지어내지 말 것. "
                        + "SELECT/WITH만 허용, 세미콜론 금지. 미설정 환경이면 404.",
                schema(Map.of("sql", strProp("DuckDB SQL (SELECT 전용)"),
                        "rowLimit", intProp("최대 행수 (기본 200, 상한 2000)"))),
                args -> post("/api/lakehouse/query",
                        mapper.createObjectNode().put("sql", args.get("sql").asText())
                                .put("rowLimit", optInt(args, "rowLimit", 200)).toString())));

        tools.put("lakehouse_card_create", new Tool(
                "질의 결과를 Metabase 카드(차트)로 저장하고 URL을 돌려준다 — \"보여줘\"의 종착지. "
                        + "전용 컬렉션 'DBTower AI'에 격리 생성(사람 대시보드 오염 방지). display: "
                        + "table|bar|line|row|area|pie 등 Metabase 표준. sql은 lakehouse_query와 같은 규칙"
                        + "(SELECT 전용 — 먼저 lakehouse_query로 결과를 확인한 뒤 만들 것).",
                schema(Map.of("title", strProp("카드 제목(한국어 권장)"),
                        "sql", strProp("카드가 실행할 DuckDB SQL (SELECT 전용)"),
                        "display", strProp("차트 타입 (기본 table)"))),
                args -> post("/api/lakehouse/cards",
                        mapper.createObjectNode().put("title", args.get("title").asText())
                                .put("sql", args.get("sql").asText())
                                .put("display", args.hasNonNull("display") ? args.get("display").asText() : "table")
                                .toString())));
    }

    // ---------- JSON-RPC 결과 조립 ----------

    private ObjectNode result(JsonNode id, ObjectNode result) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        msg.set("result", result);
        return msg;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        ObjectNode err = msg.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return msg;
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

    // ---------- REST 위임 ----------

    private String get(String path) throws Exception {
        HttpRequest req = authorized(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30)).GET()).build();
        return require2xx(http.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    private String post(String path, String jsonBody) throws Exception {
        HttpRequest req = authorized(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))).build();
        return require2xx(http.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        return apiToken == null ? builder : builder.header("Authorization", "Bearer " + apiToken);
    }

    private static String require2xx(HttpResponse<String> res) {
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("DBTower API " + res.statusCode() + ": " + res.body());
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
