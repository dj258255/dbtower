package io.dbtower.review.internal.web;

import io.dbtower.review.internal.ReviewService;
import io.dbtower.review.internal.domain.ReviewRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 스키마 변경 리뷰 게이트 API (운영 병목 아크 B2). 제출은 요청자부터(자기 팀 인스턴스),
 * 승인/반려는 승인자(SecurityConfig). POST는 AuditInterceptor가 자동 감사한다(R4).
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
                             String decidedBy, LocalDateTime decidedAt, String decisionComment,
                             String verifySql, String executedBy, LocalDateTime executedAt,
                             String rolledBackBy, LocalDateTime rolledBackAt,
                             String intervenedBy, LocalDateTime intervenedAt, String interventionNote) {
        static ReviewView of(ReviewRequest r) {
            return new ReviewView(r.getId(), r.getInstanceId(), r.getTargetSql(), r.getReason(),
                    r.getRequester(), r.getStatus().name(),
                    r.getFindings() == null ? List.of() : List.of(r.getFindings().split("\n")),
                    r.getAiOpinion(), r.getRulesVersion(), r.isParseLimited(), r.getSubmittedAt(),
                    r.getDecidedBy(), r.getDecidedAt(), r.getDecisionComment(),
                    r.getVerifySql(), r.getExecutedBy(), r.getExecutedAt(), r.getRolledBackBy(), r.getRolledBackAt(),
                    r.getIntervenedBy(), r.getIntervenedAt(), r.getInterventionNote());
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

    /** 단건 — MCP change_ticket_status가 쓴다. 팀 범위 밖이면 404. */
    @GetMapping("/reviews/{reviewId}")
    public ReviewView get(@PathVariable Long reviewId) {
        return ReviewView.of(reviewService.getScoped(reviewId));
    }

    /** 대기 중 요청 전체 — "리뷰 대기함". */
    @GetMapping("/reviews/pending")
    public List<ReviewView> pending() {
        return reviewService.pending().stream().map(ReviewView::of).toList();
    }

    /** 승인/반려 — 승인자(APPROVER, ADMIN 포함). */
    @PostMapping("/reviews/{reviewId}/decision")
    public ReviewView decide(@PathVariable Long reviewId, @RequestBody DecisionRequest req) {
        return ReviewView.of(reviewService.decide(reviewId, req.approved(), req.comment(), principal()));
    }

    public record DecisionRequest(boolean approved, String comment) {
    }

    /**
     * 취소 — 요청자 본인, 또는 남의 티켓을 닫을 책임이 있는 승인자·운영자·관리자(서비스가 확인). 대기·승인 상태에서만.
     * 사용자는 역할 하나만 가지므로 세 역할을 직접 본다(역할 계층을 따로 풀지 않아도 된다).
     */
    @PostMapping("/reviews/{reviewId}/cancel")
    public ReviewView cancel(@PathVariable Long reviewId, @RequestBody(required = false) CancelRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean privileged = auth != null && auth.getAuthorities().stream().anyMatch(a -> CAN_CANCEL_OTHERS.contains(a.getAuthority()));
        return ReviewView.of(reviewService.cancel(reviewId, req == null ? null : req.note(), principal(), privileged));
    }

    private static final Set<String> CAN_CANCEL_OTHERS = Set.of("ROLE_APPROVER", "ROLE_OPERATOR", "ROLE_ADMIN");

    public record CancelRequest(String note) {
    }

    private static String principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }
}
