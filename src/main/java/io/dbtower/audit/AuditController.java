package io.dbtower.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 감사 로그 조회·검색 — 최신순. 인가는 SecurityConfig의 /api/audit/** ADMIN 규칙이 담당한다.
 *
 * 필터를 하나도 안 주면 최신 N건(무필터 목록), 주면 그 조합으로 좁힌다.
 * 동적 필터 조립은 Specification으로 — 필터가 늘어도 메서드가 아니라 조각이 하나 는다.
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
    public List<AuditEventResponse> search(
            @RequestParam(required = false) String principal,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long instanceId,
            @RequestParam(required = false) Integer outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "50") int limit) {

        // 상한 500 — 감사 로그는 계속 쌓이는 테이블이라 무제한 limit은 사실상 풀스캔 요청이 된다
        int size = Math.min(Math.max(limit, 1), 500);
        var pageable = PageRequest.of(0, size,
                Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id")));

        // 비어 있지 않은 필터만 AND로 합친다. 하나도 없으면 무필터 목록(findAll).
        Specification<AuditEvent> spec = Stream.of(
                        AuditSpecifications.principalIs(principal),
                        AuditSpecifications.actionContains(action),
                        AuditSpecifications.instanceIdIs(instanceId),
                        AuditSpecifications.outcomeIs(outcome),
                        AuditSpecifications.occurredFrom(from),
                        AuditSpecifications.occurredTo(to))
                .filter(Objects::nonNull)
                .reduce(Specification::and)
                .orElse(null);

        Page<AuditEvent> page = (spec == null)
                ? repository.findAll(pageable)
                : repository.findAll(spec, pageable);
        return page.getContent().stream().map(AuditEventResponse::from).toList();
    }
}
