package io.dbtower.security.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 계정별 로그인 연속 실패 상태 (Phase 3, V17) — 브루트포스 잠금 카운터를 메타 DB에 둔다.
 *
 * Phase 1의 인메모리 구현은 노드마다 독립이라, LB 뒤 N노드 배포에서는 실패 허용치가 사실상
 * N배가 됐다(각 노드가 자기 실패만 셈). 세션(spring-session-jdbc)과 같은 논리로 메타 DB를
 * 공유 스토어로 쓰면 노드 수와 무관하게 임계가 지켜지고, 재시작해도 잠금이 풀리지 않는다.
 *
 * <p><b>보존: 정리 대상 아님.</b> {@code username}이 기본 키라 사용자당 한 행이고 upsert 된다 —
 * 시간이 지나도 행이 늘지 않으므로 지울 것이 없다(#148). 계정을 지우면 이 행도 함께 정리된다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginAttempt {

    /** 계정명(소문자 정규화 — 대소문자 우회 방지는 저장 전 키 정규화로 보장) */
    @Id
    @Column(length = 100)
    private String username;

    @Column(nullable = false)
    private int failures;

    private LocalDateTime lockedUntil;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public LoginAttempt(String username) {
        this.username = username;
        this.failures = 0;
        this.updatedAt = LocalDateTime.now();
    }

    /** 만료된 잠금이면 카운터를 새로 시작한다 — 잠금이 풀린 뒤의 첫 실패는 1회부터. */
    public void resetIfLockExpired(LocalDateTime now) {
        if (lockedUntil != null && lockedUntil.isBefore(now)) {
            failures = 0;
            lockedUntil = null;
        }
    }

    public void registerFailure(LocalDateTime now) {
        failures++;
        updatedAt = now;
    }

    public void lockUntil(LocalDateTime until) {
        this.lockedUntil = until;
    }

    public boolean locked(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
