package io.dbtower.review.internal.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 스키마 변경 리뷰 요청 한 건 (운영 병목 아크 B2). 제출 시 PENDING으로 저장되고, 규칙 판정
 * 스냅샷(findings·aiOpinion·rulesVersion)을 함께 굳힌다 — 규칙이 늘어도 "그때 이렇게 판정했다"가
 * 남게. ADMIN의 승인/반려로 status가 전이되며, 실행은 하지 않는다(판정·기록까지가 이 게이트의 몫).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewRequest {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false, columnDefinition = "text")
    private String targetSql;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(nullable = false)
    private String requester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(columnDefinition = "text")
    private String findings;

    @Column(columnDefinition = "text")
    private String aiOpinion;

    @Column(nullable = false)
    private int rulesVersion;

    @Column(nullable = false)
    private boolean parseLimited;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    private String decidedBy;
    private LocalDateTime decidedAt;

    @Column(columnDefinition = "text")
    private String decisionComment;

    public ReviewRequest(Long instanceId, String targetSql, String reason, String requester,
                         String findings, String aiOpinion, int rulesVersion, boolean parseLimited) {
        this.instanceId = instanceId;
        this.targetSql = targetSql;
        this.reason = reason;
        this.requester = requester;
        this.status = Status.PENDING;
        this.findings = findings;
        this.aiOpinion = aiOpinion;
        this.rulesVersion = rulesVersion;
        this.parseLimited = parseLimited;
        this.submittedAt = LocalDateTime.now();
    }

    /** 승인/반려 — 미결정(PENDING)일 때만 전이한다(중복 결정 방지는 서비스가 확인). */
    public void decide(Status decision, String decidedBy, String comment) {
        this.status = decision;
        this.decidedBy = decidedBy;
        this.decidedAt = LocalDateTime.now();
        this.decisionComment = comment;
    }
}
