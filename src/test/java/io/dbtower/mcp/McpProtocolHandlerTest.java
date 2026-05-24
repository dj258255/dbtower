package io.dbtower.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 프로토콜 코어 검증 — SDK 없이 직접 구현했으므로 스펙 준수를 테스트로 고정한다.
 * (JSON-RPC 2.0: 알림에는 응답하지 않는다 / 미지원 메서드는 -32601 / 도구 목록 스키마)
 */
class McpProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpProtocolHandler handler = new McpProtocolHandler("http://localhost:0");

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void initialize는_서버_정보와_tools_capability를_돌려준다() throws Exception {
        ObjectNode response = handler.handle(parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));

        assertNotNull(response);
        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals(1, response.get("id").asInt());
        JsonNode result = response.get("result");
        assertEquals("dbtower", result.path("serverInfo").path("name").asText());
        assertTrue(result.path("capabilities").has("tools"));
    }

    @Test
    void 알림에는_응답하지_않는다() throws Exception {
        // JSON-RPC 2.0: id가 없는 메시지는 알림 — 응답을 만들면 스펙 위반
        assertNull(handler.handle(parse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
    }

    @Test
    void 도구_8종이_이름과_입력_스키마를_갖고_노출된다() throws Exception {
        ObjectNode response = handler.handle(parse(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));

        JsonNode tools = response.get("result").get("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> {
            names.add(t.get("name").asText());
            assertFalse(t.get("description").asText().isBlank());
            assertEquals("object", t.get("inputSchema").get("type").asText());
        });
        assertTrue(names.containsAll(List.of(
                "list_instances", "health", "query_stats", "slow_queries",
                "compare", "activity", "explain", "replication")));
        assertEquals(8, names.size());
    }

    @Test
    void 미지원_메서드는_에러코드_32601로_거절한다() throws Exception {
        ObjectNode response = handler.handle(parse(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}"));

        assertEquals(-32601, response.get("error").get("code").asInt());
    }

    @Test
    void 미지원_메서드라도_알림이면_조용히_무시한다() throws Exception {
        assertNull(handler.handle(parse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"unknown/thing\"}")));
    }

    @Test
    void 없는_도구_호출은_isError_결과로_돌려준다() throws Exception {
        // MCP 스펙: 도구 실행 실패는 JSON-RPC error가 아니라 result.isError=true —
        // 에이전트(LLM)가 실패 내용을 읽고 스스로 정정할 수 있어야 하기 때문
        ObjectNode response = handler.handle(parse(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}"));

        assertEquals(4, response.get("id").asInt());
        JsonNode result = response.get("result");
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("없는 도구"));
    }
}
