package io.dbtower.security.internal.web;

import io.dbtower.security.ApiTokenProvider;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SecurityController {

    private final ApiTokenProvider tokens;

    public SecurityController(ApiTokenProvider tokens) {
        this.tokens = tokens;
    }

    /** 화면 상단의 사용자 표시용 — 로그인 주체와 역할 */
    @GetMapping("/me")
    public Map<String, String> me(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .findFirst().map(a -> a.getAuthority().replace("ROLE_", "")).orElse("?");
        return Map.of("username", authentication.getName(), "role", role);
    }

    /** MCP 연동 카드가 등록 명령을 완성할 때 사용 — ADMIN만 (SecurityConfig) */
    @GetMapping("/security/mcp-token")
    public Map<String, String> mcpToken() {
        return Map.of("token", tokens.token());
    }
}
