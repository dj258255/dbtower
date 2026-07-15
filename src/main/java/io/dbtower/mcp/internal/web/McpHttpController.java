package io.dbtower.mcp.internal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dbtower.mcp.McpProtocolHandler;
import io.dbtower.security.ApiTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 서버 — Streamable HTTP 전송 (확장5).
 *
 * 앱이 떠 있으면 MCP 연동 준비가 끝난다 — 별도 프로세스·클래스패스 준비가 필요 없어서
 * 웹 UI에서 등록 명령 한 줄 복사로 연동된다:
 *   claude mcp add --transport http dbtower http://localhost:8080/mcp
 *
 * Streamable HTTP 규약의 부분집합: 클라이언트가 JSON-RPC 메시지를 POST하면
 * 단일 application/json으로 응답한다(SSE 스트림은 서버 선택 사항이라 쓰지 않는다).
 * 알림(id 없음)은 본문 없이 202 Accepted. 상태 없는(stateless) 서버라 세션 헤더도 없다.
 *
 * 프로토콜 처리는 stdio 전송과 동일한 McpProtocolHandler — 전송만 다르고 도구는 같다.
 * 도구 실행이 자기 자신의 REST API 호출로 위임되므로, 인증·검증을 REST 한 곳에만 두면 된다.
 */
@RestController
public class McpHttpController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpProtocolHandler handler;

    public McpHttpController(@Value("${server.port:8080}") int port, ApiTokenProvider tokens) {
        // 도구 실행이 자기 REST API로 위임되므로 서비스 토큰으로 인증한다 (A1)
        this.handler = new McpProtocolHandler("http://localhost:" + port, tokens.token());
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String body) throws Exception {
        JsonNode msg = mapper.readTree(body);
        ObjectNode response = handler.handle(msg);
        if (response == null) {
            return ResponseEntity.accepted().build(); // 알림 — 응답 본문 없음
        }
        return ResponseEntity.ok(response.toString());
    }
}
