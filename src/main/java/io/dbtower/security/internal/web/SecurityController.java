package io.dbtower.security.internal.web;

import io.dbtower.security.ApiTokenProvider;
import io.dbtower.security.internal.domain.PlatformUser;
import io.dbtower.security.internal.persistence.PlatformUserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SecurityController {

    private final ApiTokenProvider tokens;
    private final PlatformUserRepository users;

    public SecurityController(ApiTokenProvider tokens, PlatformUserRepository users) {
        this.tokens = tokens;
        this.users = users;
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

    public record TeamRequest(@Size(max = 100) String teamLabel) {
    }

    /**
     * 사용자 팀 라벨 지정(LBAC, Phase 3) — ADMIN 전용(SecurityConfig의 /api/security/**).
     * null/빈 값이면 스코프 해제(전역). 스코프는 authority에 실리므로 다음 로그인부터 적용된다.
     */
    @PatchMapping("/security/users/{username}/team")
    public Map<String, String> setTeam(@PathVariable String username, @Valid @RequestBody TeamRequest req) {
        PlatformUser user = users.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("없는 사용자: " + username));
        String label = (req.teamLabel() == null || req.teamLabel().isBlank()) ? null : req.teamLabel().trim();
        user.updateTeamLabel(label);
        users.save(user);
        return Map.of("username", username, "teamLabel", label == null ? "(전역)" : label,
                "note", "다음 로그인부터 적용됩니다(스코프는 인증 시 부여)");
    }
}
