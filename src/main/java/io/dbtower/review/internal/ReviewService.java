package io.dbtower.review.internal;

import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.operator.model.TableDetail;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.RegistryService;
import io.dbtower.review.ReviewDecidedEvent;
import io.dbtower.review.ReviewSubmittedEvent;
import io.dbtower.review.internal.ChangeReviewRules.Verdict;
import io.dbtower.review.internal.domain.ReviewRequest;
import io.dbtower.review.internal.domain.ReviewRequest.Status;
import io.dbtower.review.internal.persistence.ReviewRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 스키마 변경 리뷰 게이트 서비스 (운영 병목 아크 B2). 제출 시 규칙 판정(ChangeReviewRules) +
 * 대테이블 락 위험 확인(tableDetail 행수) + AI 1차 소견(AiAnalyzer)을 붙여 PENDING으로 저장하고,
 * ADMIN이 승인/반려한다. 실행은 하지 않는다 — 승인된 MySQL DDL이면 gh-ost 화면 안내만 붙인다.
 *
 * 판정 근거는 사람이 정한 규칙(ChangeReviewRules)이고 AI는 그 위 1차 소견이다(ai-analysis-rules
 * 원칙 승계). 카드 발송은 이벤트로 alert에 위임한다(Modulith 순환 회피).
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private static final String AI_SYSTEM_PROMPT = """
            너는 DBA 변경 리뷰어다. 아래 변경 SQL과 규칙 지적을 근거로만 1차 소견을 3문장 이내로 쓴다.
            근거에 없는 위험을 지어내지 마라. 승인/반려를 단정하지 말고 사람이 확인할 점만 짚어라.
            규칙이 이미 지적한 것은 반복하지 말고 보완 관점(배포 순서·트래픽 시간대·롤백 계획 등)만 더하라.
            """;

    private final ReviewRequestRepository repository;
    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final ChangeReviewRules rules;
    private final AiAnalyzer aiAnalyzer;
    private final QueryMasker queryMasker;
    private final ApplicationEventPublisher events;

    public ReviewService(ReviewRequestRepository repository, RegistryService registryService,
                         DbmsOperatorFactory operatorFactory, AiAnalyzer aiAnalyzer,
                         QueryMasker queryMasker, ApplicationEventPublisher events) {
        this.repository = repository;
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.rules = new ChangeReviewRules();
        this.aiAnalyzer = aiAnalyzer;
        this.queryMasker = queryMasker;
        this.events = events;
    }

    public record SubmitRequest(String sql, String reason) {
    }

    /** 제출 — 규칙 판정 + 락 위험 확인 + AI 소견을 굳혀 PENDING 저장, 리뷰 카드 이벤트 발행. */
    @Transactional
    public ReviewRequest submit(Long instanceId, SubmitRequest req, String requester) {
        DatabaseInstance instance = registryService.findById(instanceId);
        Verdict verdict = rules.evaluate(req.sql());
        List<String> findings = new ArrayList<>(verdict.findings());

        // 대테이블 락 위험 확정 — ALTER 대상 테이블의 실제 행수로 온라인 DDL 권고를 강화(R2)
        verdict.alterTable().ifPresent(table -> tryRowCount(instance, table)
                .ifPresent(rows -> findings.add(rules.lockRiskLine(table, rows))));

        String aiOpinion = aiOpinion(req.sql(), findings);
        ReviewRequest saved = repository.save(new ReviewRequest(
                instanceId, req.sql(), req.reason(), requester,
                String.join("\n", findings), aiOpinion, ChangeReviewRules.VERSION, verdict.parseLimited()));

        events.publishEvent(new ReviewSubmittedEvent(saved.getId(), instanceId, requester,
                queryMasker.apply(req.sql()), findings, aiOpinion, verdict.parseLimited()));
        return saved;
    }

    /** 승인/반려 — PENDING일 때만 전이(중복 결정 거부). 결과 카드 이벤트 발행. */
    @Transactional
    public ReviewRequest decide(Long reviewId, boolean approved, String comment, String decidedBy) {
        ReviewRequest review = repository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("리뷰 요청을 찾을 수 없습니다: " + reviewId));
        if (review.getStatus() != Status.PENDING) {
            throw new IllegalStateException("이미 처리된 요청입니다: " + review.getStatus());
        }
        review.decide(approved ? Status.APPROVED : Status.REJECTED, decidedBy, comment);
        repository.save(review);

        String hint = null;
        if (approved) {
            DatabaseInstance instance = registryService.findById(review.getInstanceId());
            if (instance.getType() == DbmsType.MYSQL && isDdl(review.getTargetSql())) {
                hint = "승인된 MySQL DDL — 대형 테이블이면 온라인 DDL(gh-ost) 화면에서 dry-run 후 실행하세요. 실행은 사람이 합니다.";
            }
        }
        events.publishEvent(new ReviewDecidedEvent(reviewId, review.getInstanceId(), approved,
                decidedBy, comment, hint));
        return review;
    }

    public List<ReviewRequest> byInstance(Long instanceId) {
        // LBAC 스코프 강제 — 스코프 밖 인스턴스면 findById가 404(리뷰 SQL 노출 방지). 목록 직조회 전에 게이트한다.
        registryService.findById(instanceId);
        return repository.findByInstanceIdOrderBySubmittedAtDesc(instanceId);
    }

    public List<ReviewRequest> pending() {
        return repository.findByStatusOrderBySubmittedAtDesc(Status.PENDING);
    }

    public ReviewRequest get(Long reviewId) {
        return repository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("리뷰 요청을 찾을 수 없습니다: " + reviewId));
    }

    private Optional<Long> tryRowCount(DatabaseInstance instance, String table) {
        try {
            TableDetail detail = operatorFactory.create(instance).tableDetail(table);
            return detail.rowCount() >= 0 ? Optional.of(detail.rowCount()) : Optional.empty();
        } catch (RuntimeException e) {
            // 행수 확인 실패는 리뷰를 막지 않는다 — SQL 규칙 지적만으로도 유효하다
            log.debug("리뷰 락 위험 행수 확인 실패 table={} cause={}", table, e.getMessage());
            return Optional.empty();
        }
    }

    private String aiOpinion(String sql, List<String> findings) {
        // AI 프롬프트에도 마스킹본을 쓴다(외부로 나갈 수 있는 경로) — 토글은 QueryMasker가 관장
        String context = "변경 SQL:\n" + queryMasker.applyForAiPrompt(sql)
                + "\n\n규칙 지적:\n- " + String.join("\n- ", findings);
        return aiAnalyzer.complete(AI_SYSTEM_PROMPT, context).orElse(null);
    }

    private static boolean isDdl(String sql) {
        String s = sql.stripLeading().toLowerCase();
        return s.startsWith("alter") || s.startsWith("create") || s.startsWith("drop");
    }
}
