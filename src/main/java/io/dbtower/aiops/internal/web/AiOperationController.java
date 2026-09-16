package io.dbtower.aiops.internal.web;

import io.dbtower.aiops.AiOperationJobView;
import io.dbtower.aiops.AiOperationRequest;
import io.dbtower.aiops.AiOperationService;
import io.dbtower.aiops.AiOperationStatus;
import io.dbtower.aiops.internal.AiOperationWorkflow;
import io.dbtower.aiops.internal.OutboxRelayService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 운영 작업의 REST 경계. 두 부류의 호출자가 있다.
 *
 * <ul>
 *   <li>사람·게이트웨이 — 접수·조회·취소·재시도. 범위는 서비스가 호출자 기준으로 거른다</li>
 *   <li>실행기·릴레이(서비스 토큰) — Outbox 선점, 작업 선점과 단계 진행. 선점 뒤 단계는 X-Lease-Token이 맞아야 한다</li>
 * </ul>
 * 역할 경계는 SecurityConfig가 URL로 건다. 여기에는 비즈니스 규칙을 두지 않는다.
 */
@RestController
@RequestMapping("/api/ai-operations")
public class AiOperationController {

    private static final String LEASE = "X-Lease-Token";

    private final AiOperationService service;
    private final AiOperationWorkflow workflow;
    private final OutboxRelayService relay;

    public AiOperationController(AiOperationService service, AiOperationWorkflow workflow, OutboxRelayService relay) {
        this.service = service;
        this.workflow = workflow;
        this.relay = relay;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiOperationJobView submit(@RequestBody AiOperationRequest request) {
        return service.submit(request);
    }

    @GetMapping
    public List<AiOperationJobView> recent(@RequestParam(defaultValue = "30") int limit) {
        return service.recent(limit);
    }

    @GetMapping("/{jobId}")
    public AiOperationJobView find(@PathVariable String jobId) {
        return service.find(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    public AiOperationJobView cancel(@PathVariable String jobId) {
        return service.cancel(jobId);
    }

    @PostMapping("/{jobId}/retry")
    public AiOperationJobView retry(@PathVariable String jobId) {
        return service.retry(jobId);
    }

    // ---- 릴레이 ----

    @PostMapping("/outbox/claim")
    public List<OutboxRelayService.Claimed> claimOutbox(@RequestParam(defaultValue = "20") int limit) {
        return relay.claim(limit);
    }

    public record PublishedRequest(@NotBlank String claimToken) {
    }

    @PostMapping("/outbox/{eventId}/published")
    public ResponseEntity<Map<String, Object>> published(@PathVariable String eventId,
                                                         @RequestBody PublishedRequest request) {
        boolean ok = relay.markPublished(eventId, request.claimToken());
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.CONFLICT).body(Map.of("published", ok));
    }

    // ---- 실행기 ----

    public record ClaimRequest(String workerId) {
    }

    @PostMapping("/{jobId}/claim")
    public ResponseEntity<AiOperationWorkflow.Claimed> claim(@PathVariable String jobId,
                                                             @RequestBody(required = false) ClaimRequest request) {
        AiOperationWorkflow.Claimed claimed = workflow.claim(jobId, request == null ? null : request.workerId());
        // 범위 확인 실패로 작업이 FAILED가 됐으면 선점이 아니다 — 본문(실패 사유·알림 전용 토큰)과 함께 409
        boolean failed = claimed.job().status() == AiOperationStatus.FAILED;
        return ResponseEntity.status(failed ? HttpStatus.CONFLICT : HttpStatus.OK).body(claimed);
    }

    @PostMapping("/{jobId}/facts")
    public AiOperationWorkflow.Facts facts(@PathVariable String jobId, @RequestHeader(LEASE) String token) {
        return workflow.collect(jobId, token);
    }

    @PostMapping("/{jobId}/retrieving")
    public AiOperationJobView retrieving(@PathVariable String jobId, @RequestHeader(LEASE) String token) {
        return workflow.startRetrieval(jobId, token);
    }

    public record AnalyzeRequest(List<AiOperationWorkflow.Reference> references) {
    }

    @PostMapping("/{jobId}/analyze")
    public AiOperationJobView analyze(@PathVariable String jobId, @RequestHeader(LEASE) String token,
                                      @RequestBody(required = false) AnalyzeRequest request) {
        return workflow.analyze(jobId, token, request == null ? List.of() : request.references());
    }

    public record FailRequest(@NotBlank String reason) {
    }

    @PostMapping("/{jobId}/fail")
    public AiOperationJobView fail(@PathVariable String jobId, @RequestHeader(LEASE) String token,
                                   @RequestBody FailRequest request) {
        return workflow.fail(jobId, token, request.reason());
    }

    @GetMapping("/{jobId}/lease-view")
    public AiOperationJobView leaseView(@PathVariable String jobId, @RequestHeader(LEASE) String token) {
        return workflow.view(jobId, token);
    }

    @PostMapping("/{jobId}/notified")
    public AiOperationWorkflow.Notified notified(@PathVariable String jobId, @RequestHeader(LEASE) String token) {
        return workflow.markNotified(jobId, token);
    }
}
