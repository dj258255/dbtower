package io.dbtower.workbench.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 변경 티켓 한 번의 드라이런·실행·되돌리기 기록. 스키마의 단일 권위는 V37 마이그레이션이다.
 *
 * <p>RUNNING으로 먼저 저장하고 대상 DB 호출 뒤에 결과로 바꾼다 — 앱이 그 사이에 죽으면 RUNNING이 남아 "결과 미확인"이
 * 드러난다. DRY_RUN 기록의 rollbackAvailable은 "실행하면 이 사본으로 되돌릴 수 있다"는 예고다(드라이런 자체는 되돌릴 것이 없다).
 */
@Entity
@Table(name = "workbench_change_execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeExecution {

    public enum Action { DRY_RUN, EXECUTE, REVERT_DRY_RUN, REVERT }

    public enum Outcome { RUNNING, COMMITTED, ROLLED_BACK, FAILED, CONFLICT, UNCERTAIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reviewId;

    @Column(nullable = false)
    private Long instanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Action action;

    @Column(nullable = false, length = 16)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Outcome outcome;

    private String tableName;

    @Column(nullable = false, length = 64)
    private String statementSha256;

    private Long affectedRows;

    @Column(nullable = false)
    private boolean rollbackAvailable;

    @Column(columnDefinition = "TEXT")
    private String rollbackNote;

    @Column(columnDefinition = "TEXT")
    private String images;

    @Column(nullable = false)
    private boolean imagesEncrypted;

    private LocalDateTime imagesExpireAt;

    @Column(columnDefinition = "TEXT")
    private String schemaDiff;

    @Column(columnDefinition = "TEXT")
    private String probe;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(nullable = false)
    private String principal;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public ChangeExecution(Long reviewId, Long instanceId, Action action, String kind, String tableName,
                           String statementSha256, String principal) {
        this.reviewId = reviewId;
        this.instanceId = instanceId;
        this.action = action;
        this.kind = kind;
        this.tableName = tableName;
        this.statementSha256 = statementSha256;
        this.principal = principal;
        this.outcome = Outcome.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    public void finish(Outcome outcome, Long affectedRows, String detail) {
        this.outcome = outcome;
        this.affectedRows = affectedRows;
        this.detail = detail;
        this.finishedAt = LocalDateTime.now();
    }

    public void attachImages(String images, boolean encrypted, LocalDateTime expireAt, boolean rollbackAvailable, String note) {
        this.images = images;
        this.imagesEncrypted = encrypted;
        this.imagesExpireAt = expireAt;
        this.rollbackAvailable = rollbackAvailable;
        this.rollbackNote = note;
    }

    /** 이미 되돌린 실행 — 사본은 전후 비교 화면과 감사를 위해 보존 기한까지 남기되, 다시 되돌리는 경로는 닫는다 */
    public void markReverted(String note) {
        this.rollbackAvailable = false;
        this.rollbackNote = note;
    }

    public void closeRollback(String note) {
        this.images = null;
        this.imagesExpireAt = null;
        this.rollbackAvailable = false;
        this.rollbackNote = note;
    }

    public void attachProbe(String probe) {
        this.probe = probe;
    }

    public void attachSchemaDiff(String schemaDiff) {
        this.schemaDiff = schemaDiff;
    }
}
