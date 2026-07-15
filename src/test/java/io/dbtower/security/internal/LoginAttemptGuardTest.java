package io.dbtower.security.internal;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;

/** 로그인 브루트포스 방어 — 연속 실패 잠금, 성공 리셋, 대소문자 우회 방지. */
class LoginAttemptGuardTest {

    private static Authentication auth(String user) {
        return new UsernamePasswordAuthenticationToken(user, "x");
    }

    private void fail(LoginAttemptGuard g, String user) {
        g.onFailure(new AuthenticationFailureBadCredentialsEvent(auth(user), new BadCredentialsException("bad")));
    }

    @Test
    void 임계_미만은_잠기지_않는다() {
        LoginAttemptGuard g = new LoginAttemptGuard(3, 15);
        fail(g, "admin");
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isFalse();
    }

    @Test
    void 임계_도달시_잠긴다() {
        LoginAttemptGuard g = new LoginAttemptGuard(3, 15);
        fail(g, "admin");
        fail(g, "admin");
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isTrue();
        assertThat(g.lockRemainingSeconds("admin")).isGreaterThan(0);
    }

    @Test
    void 성공하면_카운터가_리셋된다() {
        LoginAttemptGuard g = new LoginAttemptGuard(3, 15);
        fail(g, "admin");
        fail(g, "admin");
        g.onSuccess(new AuthenticationSuccessEvent(auth("admin")));
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isFalse();  // 2회가 아니라 1회부터 다시 셈
    }

    @Test
    void 대소문자를_바꿔도_같은_계정으로_센다() {
        LoginAttemptGuard g = new LoginAttemptGuard(3, 15);
        fail(g, "Admin");
        fail(g, "ADMIN");
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isTrue();  // 대소문자 우회 불가
    }

    @Test
    void 즉시_해제_잠금이면_남은시간이_0에서_양수로() {
        LoginAttemptGuard g = new LoginAttemptGuard(1, 15);
        assertThat(g.isLocked("admin")).isFalse();
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isTrue();
    }
}
