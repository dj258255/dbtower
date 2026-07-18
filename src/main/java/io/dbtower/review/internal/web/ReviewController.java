package io.dbtower.review.internal.web;

import io.dbtower.review.internal.ReviewService;
import io.dbtower.review.internal.domain.ReviewRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 스키마 변경 리뷰 게이트 API (운영 병목 아크 B2). 제출은 인증 사용자(자기 팀 인스턴스),
 * 승인/반려는 ADMIN(SecurityConfig). POST는 AuditInterceptor가 자동 감사한다(R4).
 *
 * 응답은 원문 SQL을 담는다 — 리뷰어가 실제 DDL을 봐야 판단하므로. 조회 자체가 ADMIN 경계는
 * 아니지만(제출자도 자기 요청을 봐야 함), LBAC로 자기 팀 인스턴스만 보이는 것은 인스턴스 접근
 * 자체가 이미 스코프된다는 점에 기댄다(RegistryService.findById가 스코프 밖이면 404).
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    public record ReviewView(Long id, Long instanceId, String targetSql, String reason,
                             String requester, String status, List<String> findings, String aiOpinion,
                             int rulesVersion, boolean parseLimited, LocalDateTime submittedAt,
                             String decidedBy, LocalDateTime decidedAt, String decisionComment) {
        static ReviewView of(ReviewRequest r) {
            return new ReviewView(r.getId(), r.getInstanceId(), r.getTargetSql(), r.getReason(),
                    r.getRequester(), r.getStatus().name(),
                    r.getFindings() == null ? List.of() : List.of(r.getFindings().split("\n")),
                    r.getAiOpinion(), r.getRulesVersion(), r.isParseLimited(), r.getSubmittedAt(),
                    r.getDecidedBy(), r.getDecidedAt(), r.getDecisionComment());
        }
    }

    /** 제출 — 규칙 판정·AI 소견을 붙여 PENDING 생성. */
    @PostMapping("/instances/{id}/reviews")
    public ReviewView submit(@PathVariable Long id, @RequestBody ReviewService.SubmitRequest req) {
        return ReviewView.of(reviewService.submit(id, req, principal()));
    }

    /** 인스턴스별 리뷰 목록(최신순). */
    @GetMapping("/instances/{id}/reviews")
    public List<ReviewView> byInstance(@PathVariable Long id) {
        return reviewService.byInstance(id).stream().map(ReviewView::of).toList();
    }

    /** 대기 중 요청 전체 — "리뷰 대기함". */
    @GetMapping("/reviews/pending")
    public List<ReviewView> pending() {
        return reviewService.pending().stream().map(ReviewView::of).toList();
    }

    /** 승인/반려 — ADMIN. */
    @PostMapping("/reviews/{reviewId}/decision")
    public ReviewView decide(@PathVariable Long reviewId, @RequestBody DecisionRequest req) {
        return ReviewView.of(reviewService.decide(reviewId, req.approved(), req.comment(), principal()));
    }

    public record DecisionRequest(boolean approved, String comment) {
    }

    private static String principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }
}
