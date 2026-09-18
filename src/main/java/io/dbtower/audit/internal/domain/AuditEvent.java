package io.dbtower.audit.internal.domain;

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
 *
 * <p><b>보존: 영구.</b> 지우는 잡이 없는 것은 빠뜨린 것이 아니라 정한 것이다(#148).
 * 감사 기록은 "그때 무슨 일이 있었나"를 나중에 묻기 위한 것이라 기한을 두면 그 물음에 답할 수 없다.
 *
 * <p>대신 <b>남기는 것을 좁혀</b> 양을 잡는다({@link io.dbtower.audit.internal.AuditPolicy}) —
 * {@code /api/**}의 POST·PUT·DELETE만 남기고 GET 조회는 남기지 않는다. 화면 폴링이 빈도의 대부분이라
 * 그것까지 남기면 정작 봐야 할 상태 변경이 묻힌다. AI 운영 작업의 릴레이 선점처럼 기계가 매초 두드리는
 * 경로도 성공 응답은 뺀다(169절: 기동 20초에 빈 선점 기록 19행 — 하루면 8만 행이 감사 로그를 덮는다).
 *
 * <p>그래서 이 표는 사람의 상태 변경 수만큼만 자란다. 디스크 계획은 docs/operate/operations.md 참고.
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
