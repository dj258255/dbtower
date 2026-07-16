package io.dbtower.security.internal;

import io.dbtower.security.internal.domain.LoginAttempt;
import io.dbtower.security.internal.persistence.LoginAttemptRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 로그인 브루트포스 방어 — 연속 실패 잠금, 성공 리셋, 대소문자 우회 방지.
 * 스토어는 맵으로 흉내낸 리포지토리 — 검증 대상은 잠금 판정 로직이지 JPA가 아니다.
 * (카운터가 메타 DB로 간 이유는 노드별 인메모리가 LB 뒤에서 임계를 N배로 늘리기 때문 — V17)
 */
class LoginAttemptGuardTest {

    private static Authentication auth(String user) {
        return new UsernamePasswordAuthenticationToken(user, "x");
    }

    private void fail(LoginAttemptGuard g, String user) {
        g.onFailure(new AuthenticationFailureBadCredentialsEvent(auth(user), new BadCredentialsException("bad")));
    }

    /** 맵 기반 fake 스토어 — findById/save/deleteById만 실제처럼 동작 */
    private static LoginAttemptRepository mapRepository() {
        Map<String, LoginAttempt> db = new HashMap<>();
        LoginAttemptRepository repo = Mockito.mock(LoginAttemptRepository.class);
        when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(db.get((String) inv.getArgument(0))));
        when(repo.save(any(LoginAttempt.class))).thenAnswer(inv -> {
            LoginAttempt a = inv.getArgument(0);
            db.put(a.getUsername(), a);
            return a;
        });
        doAnswer(inv -> db.remove((String) inv.getArgument(0))).when(repo).deleteById(anyString());
        return repo;
    }

    @Test
    void 임계_미만은_잠기지_않는다() {
        LoginAttemptGuard g = new LoginAttemptGuard(mapRepository(), 3, 15);
        fail(g, "admin");
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isFalse();
    }

    @Test
    void 임계_도달시_잠긴다() {
        LoginAttemptGuard g = new LoginAttemptGuard(mapRepository(), 3, 15);
        fail(g, "admin");
        fail(g, "admin");
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isTrue();
        assertThat(g.lockRemainingSeconds("admin")).isGreaterThan(0);
    }

    @Test
    void 성공하면_카운터가_리셋된다() {
        LoginAttemptGuard g = new LoginAttemptGuard(mapRepository(), 3, 15);
        fail(g, "admin");
        fail(g, "admin");
        g.onSuccess(new AuthenticationSuccessEvent(auth("admin")));
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isFalse();  // 2회가 아니라 1회부터 다시 셈
    }

    @Test
    void 대소문자를_바꿔도_같은_계정으로_센다() {
        LoginAttemptGuard g = new LoginAttemptGuard(mapRepository(), 3, 15);
        fail(g, "Admin");
        fail(g, "ADMIN");
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isTrue();  // 대소문자 우회 불가
    }

    @Test
    void 즉시_해제_잠금이면_남은시간이_0에서_양수로() {
        LoginAttemptGuard g = new LoginAttemptGuard(mapRepository(), 1, 15);
        assertThat(g.isLocked("admin")).isFalse();
        fail(g, "admin");
        assertThat(g.isLocked("admin")).isTrue();
    }
}
