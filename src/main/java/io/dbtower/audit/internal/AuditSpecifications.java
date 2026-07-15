package io.dbtower.audit.internal;

import io.dbtower.audit.internal.domain.AuditEvent;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;

/**
 * 감사 로그 검색의 동적 필터 조각들.
 *
 * 각 빌더는 파라미터가 비어 있으면 null을 돌려준다 — "이 필터는 없다"는 뜻이다.
 * 컨트롤러가 null을 걸러 낸 뒤 AND로 합치므로, 필터 조합에 따라 WHERE가 런타임에 달라진다.
 * (정적 쿼리로는 필터 개수만큼 메서드가 폭발하는 지점 — Specification의 자연 서식지)
 */
public final class AuditSpecifications {

    private AuditSpecifications() {
    }

    /** 정확히 일치 — 감사 주체는 사용자명 또는 "api-token"이라 부분일치가 오히려 오해를 부른다 */
    public static Specification<AuditEvent> principalIs(String principal) {
        return isBlank(principal) ? null
                : (root, query, cb) -> cb.equal(root.get("principal"), principal);
    }

    /** 부분일치 — action은 "POST /api/instances/8/explain"처럼 경로라 접두/부분 검색이 유용하다 */
    public static Specification<AuditEvent> actionContains(String action) {
        return isBlank(action) ? null
                : (root, query, cb) -> cb.like(root.get("action"), "%" + action + "%");
    }

    public static Specification<AuditEvent> instanceIdIs(Long instanceId) {
        return instanceId == null ? null
                : (root, query, cb) -> cb.equal(root.get("instanceId"), instanceId);
    }

    /** 결과 코드 일치 — 예: 403만 뽑으면 "월권 시도"만, 401이면 로그인 실패만 */
    public static Specification<AuditEvent> outcomeIs(Integer outcome) {
        return outcome == null ? null
                : (root, query, cb) -> cb.equal(root.get("outcome"), outcome);
    }

    public static Specification<AuditEvent> occurredFrom(LocalDateTime from) {
        return from == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from);
    }

    public static Specification<AuditEvent> occurredTo(LocalDateTime to) {
        return to == null ? null
                : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), to);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
