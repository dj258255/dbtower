package io.dbtower.security.internal;

import io.dbtower.security.internal.PlatformRoles.Capability;
import io.dbtower.security.internal.domain.PlatformUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Set;

import static io.dbtower.security.internal.PlatformRoles.Capability.CHANGE_APPROVE;
import static io.dbtower.security.internal.PlatformRoles.Capability.CHANGE_DRY_RUN;
import static io.dbtower.security.internal.PlatformRoles.Capability.CHANGE_EXECUTE;
import static io.dbtower.security.internal.PlatformRoles.Capability.CHANGE_REQUEST;
import static io.dbtower.security.internal.PlatformRoles.Capability.OBSERVE;
import static io.dbtower.security.internal.PlatformRoles.Capability.PLATFORM_ADMIN;
import static io.dbtower.security.internal.PlatformRoles.Capability.TARGET_OPERATE;
import static io.dbtower.security.internal.PlatformRoles.Capability.WORKBENCH;
import static org.assertj.core.api.Assertions.assertThat;

/** 역할 계층과 화면 능력 — 인가(SecurityConfig)와 화면(/api/me)이 같은 정의를 쓰므로 정의 자체를 고정한다 */
class PlatformRolesTest {

    private static Set<Capability> capabilitiesOf(String... authorities) {
        return PlatformRoles.capabilities(AuthorityUtils.createAuthorityList(authorities));
    }

    @Test
    void 승인자와_운영자는_서로의_일을_갖지_않는다() {
        assertThat(capabilitiesOf("ROLE_APPROVER"))
                .contains(OBSERVE, WORKBENCH, CHANGE_REQUEST, CHANGE_APPROVE, CHANGE_DRY_RUN)
                .doesNotContain(CHANGE_EXECUTE, TARGET_OPERATE, PLATFORM_ADMIN);
        assertThat(capabilitiesOf("ROLE_OPERATOR"))
                .contains(OBSERVE, WORKBENCH, CHANGE_REQUEST, CHANGE_DRY_RUN, CHANGE_EXECUTE, TARGET_OPERATE)
                .doesNotContain(CHANGE_APPROVE, PLATFORM_ADMIN);
    }

    @Test
    void 관제는_지표만_보고_요청자는_워크벤치와_요청까지다() {
        assertThat(capabilitiesOf("ROLE_VIEWER")).containsExactly(OBSERVE);
        assertThat(capabilitiesOf("ROLE_REQUESTER")).containsExactlyInAnyOrder(OBSERVE, WORKBENCH, CHANGE_REQUEST);
    }

    @Test
    void 관리자는_모든_능력을_가진다() {
        assertThat(capabilitiesOf("ROLE_ADMIN")).containsExactlyInAnyOrder(Capability.values());
    }

    @Test
    void 모든_역할이_계층에_들어_있다() {
        // 역할을 enum에만 추가하고 계층을 빠뜨리면 그 역할의 사용자는 로그인해도 아무 입구도 열리지 않는다
        for (PlatformUser.Role role : PlatformUser.Role.values()) {
            assertThat(capabilitiesOf("ROLE_" + role.name())).as(role.name()).contains(OBSERVE);
        }
    }

    @Test
    void 계층_밖의_권한은_아무_능력도_없다() {
        assertThat(capabilitiesOf("ROLE_USER", "TEAM_team-a")).isEmpty();
    }

    @Test
    void 대표_역할은_팀_권한을_건너뛰고_가장_넓은_역할이다() {
        assertThat(PlatformRoles.primaryRole(AuthorityUtils.createAuthorityList("TEAM_team-a", "ROLE_REQUESTER"))).isEqualTo("REQUESTER");
        assertThat(PlatformRoles.primaryRole(AuthorityUtils.createAuthorityList("ROLE_VIEWER", "ROLE_OPERATOR"))).isEqualTo("OPERATOR");
        assertThat(PlatformRoles.primaryRole(AuthorityUtils.createAuthorityList("TEAM_team-a"))).isEqualTo("?");
    }

    @Test
    void 첫_화면은_요청자만_워크벤치다() {
        assertThat(PlatformRoles.home(AuthorityUtils.createAuthorityList("ROLE_REQUESTER"))).isEqualTo("/?mode=workbench");
        for (String role : new String[]{"ROLE_VIEWER", "ROLE_APPROVER", "ROLE_OPERATOR", "ROLE_ADMIN"}) {
            assertThat(PlatformRoles.home(AuthorityUtils.createAuthorityList(role))).as(role).isEqualTo("/");
        }
    }
}
