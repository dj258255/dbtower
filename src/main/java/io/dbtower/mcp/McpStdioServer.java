package io.dbtower.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * DBTower MCP 서버 — stdio 전송 (확장5).
 *
 * MCP stdio는 "한 줄 = JSON-RPC 2.0 메시지 하나" 프레이밍이다. MCP 클라이언트가
 * 이 프로세스를 직접 띄우므로 stdout에는 JSON-RPC 외에 아무것도 쓰지 않는다.
 * 프로토콜 처리는 McpProtocolHandler 공용 — HTTP 전송(POST /mcp)과 같은 코어를 쓴다.
 *
 * 실행: scripts/dbtower-mcp.sh / 대상 주소: DBTOWER_URL (기본 http://localhost:8080),
 * 인증: DBTOWER_MCP_TOKEN(MCP 전용, #99) — 없으면 DBTOWER_API_TOKEN. 앱과 같은 값이어야 한다
 */
public final class McpStdioServer {

    public static void main(String[] args) throws Exception {
        // 별도 프로세스라 앱의 토큰을 환경변수로 받는다. 도구가 부르는 REST 경로만 통하는 MCP 전용 토큰(DBTOWER_MCP_TOKEN)을 먼저 쓴다(#99).
        // 전의 설정(DBTOWER_API_TOKEN)도 도구 경로에서는 그대로 통하므로 읽는다
        String token = System.getenv("DBTOWER_MCP_TOKEN");
        if (token == null || token.isBlank()) {
            token = System.getenv("DBTOWER_API_TOKEN");
        }
        McpProtocolHandler handler = new McpProtocolHandler(
                System.getenv().getOrDefault("DBTOWER_URL", "http://localhost:8080"), token);
        ObjectMapper mapper = new ObjectMapper();
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
            ObjectNode response = handler.handle(msg);
            if (response != null) {
                System.out.println(response);
                System.out.flush();
            }
        }
    }
}
