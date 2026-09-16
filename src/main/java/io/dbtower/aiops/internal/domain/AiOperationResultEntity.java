package io.dbtower.aiops.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 작업의 사실과 결과. 사실 수집 때 만들어지고, 분석이 끝나면 소견이 붙는다.
 * 목록 값은 JSON 문자열이다 — 직렬화는 서비스가 하고 엔티티는 모른다.
 */
@Entity
@Table(name = "ai_operation_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiOperationResultEntity {

    private static final String EMPTY = "[]";

    @Id
    @Column(length = 36)
    private String jobId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String facts;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String ruleFindings;

    @Column(columnDefinition = "TEXT")
    private String aiOpinion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String evidence;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String uncertainties;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String nextActions;

    @Column(name = "reference_items", nullable = false, columnDefinition = "TEXT")
    private String references;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String unverifiedClaims;

    @Column(nullable = false)
    private boolean approvalRequired;

    @Column(length = 20)
    private String backend;

    @Column(length = 80)
    private String promptVersion;

    @Column(nullable = false)
    private OffsetDateTime collectedAt;

    private OffsetDateTime completedAt;

    /** 사실 수집 시점 — 재시도면 이전 시도의 소견까지 함께 비운다(새 사실과 옛 소견이 한 행에 섞이지 않게). */
    public AiOperationResultEntity(String jobId, String facts, String ruleFindings, String uncertainties,
                                   OffsetDateTime collectedAt) {
        this.jobId = jobId;
        recollect(facts, ruleFindings, uncertainties, collectedAt);
    }

    public void recollect(String facts, String ruleFindings, String uncertainties, OffsetDateTime collectedAt) {
        this.facts = facts;
        this.ruleFindings = ruleFindings;
        this.uncertainties = uncertainties;
        this.collectedAt = collectedAt;
        this.aiOpinion = null;
        this.evidence = EMPTY;
        this.nextActions = EMPTY;
        this.references = EMPTY;
        this.unverifiedClaims = EMPTY;
        this.approvalRequired = false;
        this.backend = null;
        this.promptVersion = null;
        this.completedAt = null;
    }

    public void conclude(String aiOpinion, String evidence, String uncertainties, String nextActions,
                         String references, String unverifiedClaims, boolean approvalRequired,
                         String backend, String promptVersion, OffsetDateTime completedAt) {
        this.aiOpinion = aiOpinion;
        this.evidence = evidence;
        this.uncertainties = uncertainties;
        this.nextActions = nextActions;
        this.references = references;
        this.unverifiedClaims = unverifiedClaims;
        this.approvalRequired = approvalRequired;
        this.backend = backend;
        this.promptVersion = promptVersion;
        this.completedAt = completedAt;
    }
}
