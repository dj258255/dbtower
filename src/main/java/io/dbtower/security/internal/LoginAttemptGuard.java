package io.dbtower.security.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 브루트포스 방어 (Phase 1). 계정명별 연속 실패를 세고, 임계 초과 시 일정 시간 잠근다.
 *
 * 새 라이브러리(Bucket4j 등) 없이 인메모리 ConcurrentHashMap으로 구현한다 — 감사 로그(감사 모듈)가
 * 실패 이벤트를 기록만 한다면, 이쪽은 그 실패에 실제로 반응해 문을 잠근다. 실패는 인증 이벤트로 세고
 * (로그인 처리는 시큐리티 필터 안에서 끝나 인터셉터에 안 잡히므로), 성공하면 즉시 카운터를 비운다.
 *
 * 한계(정직): 인메모리라 노드마다 독립이다. 다중 노드 배포에서는 각 노드가 자기 실패만 센다 —
 * 완전한 분산 잠금은 Phase 3의 공유 세션 스토어와 함께 재검토한다. 단일 노드에선 충분하다.
 * IP 기준이 아니라 계정명 기준인 이유: 프록시 뒤 IP는 forward-headers 신뢰가 전제라 별도 축으로 둔다.
 */
@Component
public class LoginAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);

    private final int maxAttempts;
    private final Duration lockDuration;

    private final Map<String, State> byUser = new ConcurrentHashMap<>();

    public LoginAttemptGuard(@Value("${dbtower.security.login-lock.max-attempts:10}") int maxAttempts,
                             @Value("${dbtower.security.login-lock.lock-minutes:15}") int lockMinutes) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.lockDuration = Duration.ofMinutes(Math.max(1, lockMinutes));
    }

    /** 잠금 상태 — 연속 실패 수와 잠금 해제 시각(잠기지 않았으면 null) */
    private static final class State {
        int failures;
        Instant lockedUntil;
    }

    public boolean isLocked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        State s = byUser.get(key(username));
        return s != null && s.lockedUntil != null && s.lockedUntil.isAfter(Instant.now());
    }

    public long lockRemainingSeconds(String username) {
        State s = byUser.get(key(username));
        if (s == null || s.lockedUntil == null) {
            return 0;
        }
        long secs = Duration.between(Instant.now(), s.lockedUntil).toSeconds();
        return Math.max(0, secs);
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String user = event.getAuthentication().getName();
        if (user == null || user.isBlank()) {
            return;
        }
        byUser.compute(key(user), (k, s) -> {
            State state = (s == null) ? new State() : s;
            // 잠금이 이미 만료됐으면 카운터를 새로 시작한다
            if (state.lockedUntil != null && state.lockedUntil.isBefore(Instant.now())) {
                state.failures = 0;
                state.lockedUntil = null;
            }
            state.failures++;
            if (state.failures >= maxAttempts && state.lockedUntil == null) {
                state.lockedUntil = Instant.now().plus(lockDuration);
                log.warn("로그인 잠금 — 계정={} 연속 실패 {}회, {}분 잠금", k, state.failures, lockDuration.toMinutes());
            }
            return state;
        });
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String user = event.getAuthentication().getName();
        if (user != null && !user.isBlank()) {
            byUser.remove(key(user));
        }
    }

    /** 대소문자 무시 — 계정명 대소문자를 바꿔 잠금을 우회하지 못하게 */
    private static String key(String username) {
        return username.trim().toLowerCase();
    }
}
