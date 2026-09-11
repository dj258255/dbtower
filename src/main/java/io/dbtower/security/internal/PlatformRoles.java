package io.dbtower.security.internal;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 역할의 포함 관계와, 화면이 쓰는 능력·첫 화면을 한 곳에서 정한다.
 *
 * <p>인가(SecurityConfig)와 화면 표시(/api/me)가 같은 정의를 쓰게 하려고 모았다. 화면은 역할 이름이 아니라 능력으로 버튼을 가른다 —
 * 역할이 늘거나 포함 관계가 바뀌어도 화면 분기는 그대로다.
 */
public final class PlatformRoles {

    /** Spring Security 역할 계층 — APPROVER와 OPERATOR는 서로를 포함하지 않는다(승인과 실행을 나눈다) */
    public static final String HIERARCHY = """
            ROLE_ADMIN > ROLE_APPROVER
            ROLE_ADMIN > ROLE_OPERATOR
            ROLE_APPROVER > ROLE_REQUESTER
            ROLE_OPERATOR > ROLE_REQUESTER
            ROLE_REQUESTER > ROLE_VIEWER
            """;

    /** 대표 역할을 고를 때의 우선순위 — 부여된 역할 중 가장 넓은 것 */
    private static final List<String> ORDER = List.of("ADMIN", "OPERATOR", "APPROVER", "REQUESTER", "VIEWER");

    private static final RoleHierarchy HIERARCHY_IMPL = RoleHierarchyImpl.fromHierarchy(HIERARCHY);

    public enum Capability {
        /** 관제 지표·리포트 조회 */
        OBSERVE,
        /** 워크벤치 — 대상 DB의 행 값을 조회 계정으로 본다 */
        WORKBENCH,
        /** 변경 요청 제출·자기 요청 취소 */
        CHANGE_REQUEST,
        /** 변경 요청 승인·반려 */
        CHANGE_APPROVE,
        /** 승인 전·실행 전 드라이런(실행 뒤 롤백) */
        CHANGE_DRY_RUN,
        /** 승인 티켓 실행·되돌리기·커밋 불명 정리 */
        CHANGE_EXECUTE,
        /** 백업·복원 검증·세션 종료·심층 진단·온라인 DDL처럼 대상 DB에 닿는 운영 */
        TARGET_OPERATE,
        /** 인스턴스·접속 계정·보안·감사·정책 설정 */
        PLATFORM_ADMIN
    }

    private PlatformRoles() {
    }

    public static RoleHierarchy hierarchy() {
        return HIERARCHY_IMPL;
    }

    public static Set<Capability> capabilities(Collection<? extends GrantedAuthority> granted) {
        Set<String> reach = HIERARCHY_IMPL.getReachableGrantedAuthorities(granted).stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        EnumSet<Capability> caps = EnumSet.noneOf(Capability.class);
        if (reach.contains("ROLE_VIEWER")) {
            caps.add(Capability.OBSERVE);
        }
        if (reach.contains("ROLE_REQUESTER")) {
            caps.add(Capability.WORKBENCH);
            caps.add(Capability.CHANGE_REQUEST);
        }
        if (reach.contains("ROLE_APPROVER")) {
            caps.add(Capability.CHANGE_APPROVE);
            caps.add(Capability.CHANGE_DRY_RUN);
        }
        if (reach.contains("ROLE_OPERATOR")) {
            caps.add(Capability.CHANGE_DRY_RUN);
            caps.add(Capability.CHANGE_EXECUTE);
            caps.add(Capability.TARGET_OPERATE);
        }
        if (reach.contains("ROLE_ADMIN")) {
            caps.add(Capability.PLATFORM_ADMIN);
        }
        return caps;
    }

    /** 부여된 ROLE_ 권한 중 가장 넓은 역할 이름(팀 권한 TEAM_은 역할이 아니다). 없으면 "?" */
    public static String primaryRole(Collection<? extends GrantedAuthority> granted) {
        Set<String> roles = granted.stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toSet());
        return ORDER.stream().filter(roles::contains).findFirst().orElse("?");
    }

    /**
     * 로그인 뒤 첫 화면 — 요청자는 데이터를 들고 오니 워크벤치 모드, 관제·승인·운영·관리는 관제 모드.
     * 두 화면은 한 페이지의 모드다(149절). 예전 주소 /workbench.html은 이 주소로 넘기는 페이지로 남아 있다
     */
    public static String home(Collection<? extends GrantedAuthority> granted) {
        return "REQUESTER".equals(primaryRole(granted)) ? "/?mode=workbench" : "/";
    }
}
