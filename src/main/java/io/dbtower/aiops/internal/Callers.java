package io.dbtower.aiops.internal;

import io.dbtower.registry.DatabaseInstance;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 호출 스레드의 인증 주체를 AI 운영 작업의 언어(요청자·자동화·팀 범위)로 옮긴다.
 *
 * <p>팀 범위 규칙은 RegistryService와 같다: ADMIN은 전역, TEAM_라벨이 있으면 그 팀, 라벨이 없으면 전역.
 * 여기서 다시 읽는 이유는 작업이 접수 시점의 범위를 저장해야 해서다 — 실행기는 전역 서비스 토큰으로 돌기 때문에
 * 실행 순간의 주체로는 원래 요청자의 범위를 알 수 없다.</p>
 */
@Component
public class Callers {

    /** ApiTokenFilter가 서비스 토큰 요청에 붙이는 주체 이름 */
    static final String AUTOMATION_PRINCIPAL = "api-token";

    public record Caller(String name, boolean automation, boolean admin, boolean operator, String team) {
        /** 전역 범위인가 — ADMIN·자동화·팀 라벨 없는 사용자 */
        public boolean global() {
            return team == null;
        }
    }

    private final RoleHierarchy roleHierarchy;

    public Callers(RoleHierarchy roleHierarchy) {
        this.roleHierarchy = roleHierarchy;
    }

    /** 팀 범위 판정 — RegistryService와 같은 규칙: 전역 범위이거나, 라벨 없는 인스턴스이거나, 같은 팀 */
    public static boolean inTeam(DatabaseInstance instance, String team) {
        return team == null || instance.getTeamLabel() == null || instance.getTeamLabel().isBlank()
                || team.equals(instance.getTeamLabel());
    }

    public Caller current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // 스케줄러·경보 리스너처럼 인증 컨텍스트가 없는 호출 — 플랫폼 자신이 올린 작업이다
            return new Caller("system", true, true, true, null);
        }
        boolean admin = false;
        boolean operator = false;
        String team = null;
        for (GrantedAuthority a : roleHierarchy.getReachableGrantedAuthorities(auth.getAuthorities())) {
            String name = a.getAuthority();
            if ("ROLE_ADMIN".equals(name)) {
                admin = true;
            } else if ("ROLE_OPERATOR".equals(name)) {
                operator = true;
            } else if (name.startsWith("TEAM_")) {
                team = name.substring("TEAM_".length());
            }
        }
        boolean automation = AUTOMATION_PRINCIPAL.equals(auth.getName());
        return new Caller(auth.getName(), automation, admin, operator || admin, admin ? null : team);
    }
}
