package io.dbtower.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 감사 로그 조회 — 최신순. 인가는 SecurityConfig의 /api/audit/** ADMIN 규칙이 담당한다.
 */
@RestController
public class AuditController {

    private final AuditEventRepository repository;

    public AuditController(AuditEventRepository repository) {
        this.repository = repository;
    }

    public record AuditEventResponse(Long id, LocalDateTime occurredAt, String principal, String role,
                                     String action, Long instanceId, int outcome, Long durationMs) {
        static AuditEventResponse from(AuditEvent e) {
            return new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getPrincipal(), e.getRole(),
                    e.getAction(), e.getInstanceId(), e.getOutcome(), e.getDurationMs());
        }
    }

    @GetMapping("/api/audit")
    public List<AuditEventResponse> latest(@RequestParam(defaultValue = "50") int limit) {
        // 상한 500 — 감사 로그는 계속 쌓이는 테이블이라 무제한 limit은 사실상 풀스캔 요청이 된다
        int size = Math.min(Math.max(limit, 1), 500);
        return repository.findAllByOrderByOccurredAtDescIdDesc(PageRequest.of(0, size))
                .stream().map(AuditEventResponse::from).toList();
    }
}
