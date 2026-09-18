package io.dbtower.workbench.internal.domain;

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
 * 워크벤치 실행 기록 한 건. 스키마의 단일 권위는 V34 마이그레이션이다.
 *
 * <p><b>보존: 영구.</b> {@link io.dbtower.audit.internal.domain.AuditEvent}와 같은 이유다(#148) —
 * "누가 어느 대상에서 무엇을 봤나"는 나중에 묻기 위한 기록이라 기한을 두면 답할 수 없다.
 * 인스턴스를 지워도 남게 FK 를 걸지 않은 것도 같은 판단이다(V34 주석).
 *
 * <p>양이 걱정되는 표는 아니다. 사람이 워크벤치에서 조회를 실행할 때 한 행이라 자동 수집과 달리
 * 사람 수와 사용량만큼만 자란다 — 1초마다 쌓이는 {@code ash_sample}(하루 200만 행 규모, #149)과는
 * 성격이 다르다. 내보내기 사유·마스킹한 열까지 담아 행은 크지만 행 수가 적다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkbenchQueryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false)
    private String principal;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(nullable = false, length = 20)
    private String tier;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String statement;

    @Column(nullable = false, length = 20)
    private String outcome;

    private Integer rowCount;

    private Boolean truncated;

    @Column(length = 500)
    private String maskedColumns;

    private Long elapsedMs;

    @Column(length = 500)
    private String reason;

    @Column(length = 500)
    private String error;

    public WorkbenchQueryLog(String principal, Long instanceId, String action, String tier, String kind,
                             String statement, String outcome, Integer rowCount, Boolean truncated,
                             String maskedColumns, Long elapsedMs, String reason, String error) {
        this.occurredAt = LocalDateTime.now();
        this.principal = principal;
        this.instanceId = instanceId;
        this.action = action;
        this.tier = tier;
        this.kind = kind;
        this.statement = statement;
        this.outcome = outcome;
        this.rowCount = rowCount;
        this.truncated = truncated;
        this.maskedColumns = maskedColumns;
        this.elapsedMs = elapsedMs;
        this.reason = reason;
        this.error = error;
    }
}
