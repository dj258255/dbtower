package io.dbtower.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 감사 이벤트 한 건 — "누가 언제 무엇을 했고 결과가 어땠나".
 * 스키마의 단일 권위는 마이그레이션(V2__audit_event.sql) — 이 엔티티는 validate 대상이다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    /** 사용자명 또는 "api-token"(Bearer 서비스 계정 — ApiTokenFilter가 부여하는 이름) */
    @Column(nullable = false)
    private String principal;

    /** VIEWER/ADMIN. 로그인 실패처럼 역할이 확정되기 전이면 null */
    private String role;

    /** HTTP 메서드 + 경로 요약 (예: "POST /api/instances"), 로그인은 "LOGIN" */
    @Column(nullable = false, length = 500)
    private String action;

    /** 경로가 특정 인스턴스를 가리키면 그 id (/api/instances/{id}/...), 아니면 null */
    private Long instanceId;

    /** HTTP 상태 코드 — 성공/거부/실패를 한 축으로 표현한다 (로그인도 200/401로 환산) */
    @Column(nullable = false)
    private int outcome;

    /** 요청 처리 시간(ms). 요청 단위가 아닌 이벤트(로그인·인가 거부)는 null */
    private Long durationMs;

    public AuditEvent(LocalDateTime occurredAt, String principal, String role, String action,
                      Long instanceId, int outcome, Long durationMs) {
        this.occurredAt = occurredAt;
        this.principal = principal;
        this.role = role;
        this.action = action;
        this.instanceId = instanceId;
        this.outcome = outcome;
        this.durationMs = durationMs;
    }
}
