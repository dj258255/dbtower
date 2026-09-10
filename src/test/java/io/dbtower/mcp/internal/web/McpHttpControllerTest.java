package io.dbtower.mcp.internal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** MCP HTTP 전송은 도구 위임에 서비스 토큰이 아니라 그 요청을 인증한 호출자의 토큰을 싣는다 — REST가 요청자·감사 주체를 사람으로 보게. */
class McpHttpControllerTest {

    private static final String LIST_INSTANCES =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_instances\",\"arguments\":{}}}";

    private HttpServer server;
    private final List<String> delegatedAuthorization = new CopyOnWriteArrayList<>();
    private McpHttpController controller;

    @BeforeEach
    void startMockRest() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/instances", ex -> {
            delegatedAuthorization.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        controller = new McpHttpController("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 도구_위임은_요청을_인증한_호출자의_토큰으로_간다() throws Exception {
        controller.handle(LIST_INSTANCES, "Bearer viewer-oauth-token");
        controller.handle(LIST_INSTANCES, "Bearer admin-oauth-token");

        assertEquals(List.of("Bearer viewer-oauth-token", "Bearer admin-oauth-token"), delegatedAuthorization);
    }

    @Test
    void Bearer_토큰이_없으면_위임하지_않고_401이다() throws Exception {
        assertEquals(401, controller.handle(LIST_INSTANCES, null).getStatusCode().value());
        assertEquals(401, controller.handle(LIST_INSTANCES, "Basic abc").getStatusCode().value());

        assertTrue(delegatedAuthorization.isEmpty());
    }

    @Test
    void 세션용_도구_목록은_MCP_코어의_tools_list와_같고_위임을_하지_않는다() throws Exception {
        JsonNode tools = new ObjectMapper().readTree(controller.tools().getBody());

        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.get("name").asText()));
        assertEquals(19, names.size(), names.toString());
        assertTrue(names.contains("change_ticket_status"));
        assertTrue(delegatedAuthorization.isEmpty());
    }
}
