package io.dbtower.security.internal;

import io.dbtower.security.internal.domain.LoginAttempt;
import io.dbtower.security.internal.persistence.LoginAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 로그인 브루트포스 방어 (Phase 1 → Phase 3에서 공유 스토어로 이관). 계정명별 연속 실패를 세고,
 * 임계 초과 시 일정 시간 잠근다. 실패는 인증 이벤트로 세고(로그인 처리는 시큐리티 필터 안에서
 * 끝나 인터셉터에 안 잡히므로), 성공하면 즉시 카운터를 비운다.
 *
 * 카운터는 메타 DB(login_attempt, V17)에 둔다 — Phase 1의 인메모리 구현은 노드마다 독립이라
 * LB 뒤 N노드에서 실패 허용치가 사실상 N배가 됐고(각 노드가 자기 실패만 셈), 재시작하면 잠금이
 * 풀렸다. 공유 세션(V15)과 같은 "메타 DB 재사용" 논리로 두 구멍을 함께 막는다.
 *
 * 동시성(정직): 두 노드가 같은 계정의 실패를 동시에 쓰면 read-modify-write라 카운트 하나가 유실될
 * 수 있다. 잠금 임계(기본 10회) 스케일에서 한두 회 유실은 잠금을 무력화하지 못하고 시점만 미세하게
 * 늦출 뿐이라, 비관/낙관 락 비용 대신 이 오차를 감수한다(위양성도 없다 — 실패만 세므로).
 * IP 기준이 아니라 계정명 기준인 이유: 프록시 뒤 IP는 forward-headers 신뢰가 전제라 별도 축으로 둔다.
 */
@Component
public class LoginAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);

    private final LoginAttemptRepository repository;
    private final int maxAttempts;
    private final Duration lockDuration;

    public LoginAttemptGuard(LoginAttemptRepository repository,
                             @Value("${dbtower.security.login-lock.max-attempts:10}") int maxAttempts,
                             @Value("${dbtower.security.login-lock.lock-minutes:15}") int lockMinutes) {
        this.repository = repository;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.lockDuration = Duration.ofMinutes(Math.max(1, lockMinutes));
    }

    @Transactional(readOnly = true)
    public boolean isLocked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return repository.findById(key(username))
                .map(a -> a.locked(LocalDateTime.now()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public long lockRemainingSeconds(String username) {
        return repository.findById(key(username))
                .map(a -> a.getLockedUntil() == null ? 0
                        : Duration.between(LocalDateTime.now(), a.getLockedUntil()).toSeconds())
                .map(secs -> Math.max(0, secs))
                .orElse(0L);
    }

    @Transactional
    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String user = event.getAuthentication().getName();
        if (user == null || user.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LoginAttempt attempt = repository.findById(key(user))
                .orElseGet(() -> new LoginAttempt(key(user)));
        attempt.resetIfLockExpired(now);
        attempt.registerFailure(now);
        if (attempt.getFailures() >= maxAttempts && attempt.getLockedUntil() == null) {
            attempt.lockUntil(now.plus(lockDuration));
            log.warn("로그인 잠금 — 계정={} 연속 실패 {}회, {}분 잠금",
                    attempt.getUsername(), attempt.getFailures(), lockDuration.toMinutes());
        }
        repository.save(attempt);
    }

    @Transactional
    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String user = event.getAuthentication().getName();
        if (user != null && !user.isBlank()) {
            repository.deleteById(key(user));
        }
    }

    /** 대소문자 무시 — 계정명 대소문자를 바꿔 잠금을 우회하지 못하게 */
    private static String key(String username) {
        return username.trim().toLowerCase();
    }
}
