package io.dbtower.mcp.internal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dbtower.mcp.McpProtocolHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * 그 전제가 서려면 REST가 호출자 자신을 주체로 봐야 한다 — 그래서 서비스 토큰이 아니라 이 요청의 Bearer 토큰을 싣는다.
 */
@RestController
public class McpHttpController {

    private static final String BEARER = "Bearer ";
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpProtocolHandler handler;

    @Autowired
    public McpHttpController(@Value("${server.port:8080}") int port) {
        this("http://localhost:" + port);
    }

    McpHttpController(String baseUrl) {
        this.handler = new McpProtocolHandler(baseUrl);
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String body,
                                         @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                         String authorization) throws Exception {
        // 서비스 토큰으로 위임하던 때는 OAuth로 로그인한 관리자가 MCP로 올린 변경 요청의 요청자가 api-token으로 남아, 같은 사람이
        // 화면에서 자기 요청을 승인해도 요청자·승인자 분리가 막지 못했다(132절). /mcp 체인은 Bearer 필터로만 인증하니 정상 요청엔
        // 토큰이 있다 — 없으면 위임하지 않는다.
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode msg = mapper.readTree(body);
        ObjectNode response = handler.withToken(authorization.substring(BEARER.length())).handle(msg);
        if (response == null) {
            return ResponseEntity.accepted().build(); // 알림 — 응답 본문 없음
        }
        return ResponseEntity.ok(response.toString());
    }

    /**
     * 대시보드 MCP 카드의 도구 목록. /mcp는 Bearer 전용 체인이라 콘솔 세션으로 부르면 401이어서(91절) 카드가 목록 대신
     * 안내 문구만 보였다. 도구 이름·설명은 비밀이 아니고 위임도 일어나지 않으니, 같은 코어의 tools/list를 세션 경로로 연다.
     */
    @GetMapping(value = "/api/mcp/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> tools() throws Exception {
        ObjectNode response = handler.handle(mapper.readTree(TOOLS_LIST));
        return ResponseEntity.ok(response.path("result").path("tools").toString());
    }
}
