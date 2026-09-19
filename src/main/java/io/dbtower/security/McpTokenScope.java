package io.dbtower.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * MCP 전용 토큰이 인증되는 자리(#99).
 *
 * <p>MCP HTTP는 받은 토큰을 그대로 들고 REST를 다시 부른다 — 요청자 신원이 서비스 토큰으로 바뀌지 않게 하려는 것이다(132절).
 * 그래서 MCP 토큰을 REST에서 통째로 막으면 도구가 멈춘다. 대신 MCP 도구가 실제로 부르는 경로만 연다 — MCP 클라이언트 설정에 넣은 토큰이
 * 새도 사용자·보안·설정·변경 실행 API는 열리지 않는다. 도구를 추가하면 여기에 경로를 더한다(McpTokenScopeCoverageTest가 빠뜨림을 잡는다).
 */
public final class McpTokenScope {

    private record Route(String method, Pattern path) {
    }

    private static final String ID = "\\d+";
    private static final List<Route> ROUTES = List.of(
            get("/mcp"), post("/mcp"),
            get("/api/instances"),
            get("/api/instances/" + ID + "/(health|query-stats|slow-queries|compare|activity|metrics|wait-events|replication|sessions|schema|partitions)"),
            post("/api/instances/" + ID + "/(explain|reviews)"),
            get("/api/schema-diff"),
            get("/api/reviews/" + ID),
            get("/api/workbench/tickets/" + ID + "/executions"),
            post("/api/workbench/instances/" + ID + "/agent-query"));

    private McpTokenScope() {
    }

    private static Route get(String regex) {
        return new Route("GET", Pattern.compile(regex));
    }

    private static Route post(String regex) {
        return new Route("POST", Pattern.compile(regex));
    }

    /** 경로는 쿼리 문자열을 뺀 요청 URI다 */
    public static boolean allows(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        return ROUTES.stream().anyMatch(r -> r.method().equalsIgnoreCase(method) && r.path().matcher(path).matches());
    }

    public static boolean isMcpEndpoint(String path) {
        return "/mcp".equals(path);
    }
}
