package io.dbtower.security.internal.web;

import io.dbtower.security.ApiTokenProvider;
import io.dbtower.security.internal.PlatformRoles;
import io.dbtower.security.internal.domain.PlatformUser;
import io.dbtower.security.internal.persistence.PlatformUserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SecurityController {

    private final ApiTokenProvider tokens;
    private final PlatformUserRepository users;
    private final PasswordEncoder encoder;

    public SecurityController(ApiTokenProvider tokens, PlatformUserRepository users, PasswordEncoder encoder) {
        this.tokens = tokens;
        this.users = users;
        this.encoder = encoder;
    }

    public record MeView(String username, String role, List<String> capabilities, String home) {
    }

    /**
     * 로그인 주체·대표 역할·능력·첫 화면. 화면은 역할 이름이 아니라 capabilities로 버튼과 메뉴를 가른다.
     * 대표 역할은 ROLE_ 권한에서 고른다 — 예전엔 첫 권한을 그대로 써서 순서에 따라 팀 권한(TEAM_)이 역할로 보일 수 있었다.
     */
    @GetMapping("/me")
    public MeView me(Authentication authentication) {
        var granted = authentication.getAuthorities();
        return new MeView(authentication.getName(), PlatformRoles.primaryRole(granted),
                PlatformRoles.capabilities(granted).stream().map(Enum::name).toList(), PlatformRoles.home(granted));
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

    /** 사용자 목록 — 비밀번호 해시는 싣지 않는다 */
    public record UserView(String username, String role, String teamLabel) {
        static UserView of(PlatformUser u) {
            return new UserView(u.getUsername(), u.getRole().name(), u.getTeamLabel());
        }
    }

    @GetMapping("/security/users")
    public List<UserView> users() {
        return users.findAll().stream().sorted(Comparator.comparing(PlatformUser::getUsername)).map(UserView::of).toList();
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,50}", message = "username은 영문·숫자·._- 3~50자") String username,
            @NotBlank @Size(min = 12, max = 200, message = "비밀번호는 12자 이상") String password,
            @NotNull PlatformUser.Role role,
            @Size(max = 100) String teamLabel) {
    }

    /** 사람별 역할로 사용자를 만든다 — ADMIN 전용. 이름이 겹치면 409 */
    @PostMapping("/security/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest req) {
        if (users.findByUsername(req.username()).isPresent()) {
            throw new IllegalStateException("이미 있는 사용자: " + req.username());
        }
        PlatformUser user = new PlatformUser(req.username(), encoder.encode(req.password()), req.role());
        if (req.teamLabel() != null && !req.teamLabel().isBlank()) {
            user.updateTeamLabel(req.teamLabel().trim());
        }
        return UserView.of(users.save(user));
    }

    public record RoleRequest(@NotNull PlatformUser.Role role) {
    }

    /**
     * 역할 변경 — ADMIN 전용, 다음 로그인부터 적용(역할은 인증 시 부여). 마지막 ADMIN을 낮추면 아무도 역할·보안을 되돌릴 수 없으므로 거부한다.
     */
    @PatchMapping("/security/users/{username}/role")
    public Map<String, String> setRole(@PathVariable String username, @Valid @RequestBody RoleRequest req) {
        PlatformUser user = users.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("없는 사용자: " + username));
        long admins = users.findAll().stream().filter(u -> u.getRole() == PlatformUser.Role.ADMIN).count();
        if (user.getRole() == PlatformUser.Role.ADMIN && req.role() != PlatformUser.Role.ADMIN && admins <= 1) {
            throw new IllegalStateException("마지막 ADMIN의 역할은 낮출 수 없습니다");
        }
        user.updateRole(req.role());
        users.save(user);
        return Map.of("username", username, "role", req.role().name(), "note", "다음 로그인부터 적용됩니다(역할은 인증 시 부여)");
    }
}
