package io.dbtower.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.dbtower.security.McpTokenScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 도구가 부르는 REST 경로는 전부 MCP 토큰의 자리 안에 있다(#99).
 *
 * <p>도구를 하나씩 실제로 불러 나간 요청을 모은다 — 도구를 추가하고 {@link McpTokenScope}에 경로를 빠뜨리면 MCP 토큰으로 그 도구가
 * 401을 받는다. 그 실수를 배포 전에 잡는다.
 */
class McpTokenScopeCoverageTest {

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] body = (exchange.getRequestURI().getPath().endsWith("/instances") ? "[]" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 모든_도구의_REST_경로가_MCP_토큰_자리_안에_있다() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler("http://127.0.0.1:" + server.getAddress().getPort(), "t");
        JsonNode tools = handler.handle(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .path("result").path("tools");
        List<String> names = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            names.add(name);
            ObjectNode args = mapper.createObjectNode();
            tool.path("inputSchema").path("properties").fields().forEachRemaining(p -> {
                String type = p.getValue().path("type").asText();
                if ("integer".equals(type) || "number".equals(type)) args.put(p.getKey(), 1);
                else args.put(p.getKey(), p.getKey().toLowerCase().contains("sql") ? "SELECT 1" : "2026-09-18T00:00:00");
            });
            ObjectNode call = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call");
            call.putObject("params").put("name", name).set("arguments", args);
            handler.handle(call);
        }
        System.out.printf("MEASURE 도구 %d개, REST 호출 %d건: %s%n", names.size(), calls.size(), calls);
        assertThat(names).isNotEmpty();
        assertThat(calls).as("도구가 REST를 한 번도 부르지 않았다 — 인자 채우기가 틀렸다").hasSizeGreaterThanOrEqualTo(names.size() - 2);
        assertThat(calls).allSatisfy(c -> {
            String[] mp = c.split(" ", 2);
            assertThat(McpTokenScope.allows(mp[0], mp[1])).as("MCP 토큰 자리에 없는 도구 경로: %s", c).isTrue();
        });
    }
}
