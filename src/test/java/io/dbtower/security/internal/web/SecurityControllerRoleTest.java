package io.dbtower.security.internal.web;

import io.dbtower.security.internal.domain.PlatformUser;
import io.dbtower.security.internal.persistence.PlatformUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 역할 변경의 잠김 방지 — 마지막 ADMIN을 낮추면 역할·보안을 되돌릴 사람이 없어진다 */
class SecurityControllerRoleTest {

    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private final SecurityController controller = new SecurityController(null, users, new BCryptPasswordEncoder());

    @Test
    void 마지막_관리자는_낮출_수_없다() {
        PlatformUser admin = new PlatformUser("admin", "hash", PlatformUser.Role.ADMIN);
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(users.findAll()).thenReturn(List.of(admin, new PlatformUser("op", "hash", PlatformUser.Role.OPERATOR)));

        assertThatThrownBy(() -> controller.setRole("admin", new SecurityController.RoleRequest(PlatformUser.Role.OPERATOR)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(admin.getRole()).isEqualTo(PlatformUser.Role.ADMIN);
        verify(users, never()).save(any());
    }

    @Test
    void 관리자가_둘이면_한_명은_낮출_수_있다() {
        PlatformUser admin = new PlatformUser("admin", "hash", PlatformUser.Role.ADMIN);
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(users.findAll()).thenReturn(List.of(admin, new PlatformUser("admin2", "hash", PlatformUser.Role.ADMIN)));

        controller.setRole("admin", new SecurityController.RoleRequest(PlatformUser.Role.APPROVER));

        assertThat(admin.getRole()).isEqualTo(PlatformUser.Role.APPROVER);
        verify(users).save(admin);
    }
}
