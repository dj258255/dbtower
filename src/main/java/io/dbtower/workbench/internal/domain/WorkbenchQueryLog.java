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

/** 워크벤치 실행 기록 한 건. 스키마의 단일 권위는 V34 마이그레이션이다. */
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
