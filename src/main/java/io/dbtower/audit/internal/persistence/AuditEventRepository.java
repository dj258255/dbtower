package io.dbtower.audit.internal.persistence;

import io.dbtower.audit.internal.domain.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * JpaSpecificationExecutor를 함께 상속하는 이유: 감사 로그 검색은 필터(사용자·결과·기간·인스턴스)가
 * 있을 수도 없을 수도 있는 동적 조건이라, 정적 @Query나 파생 메서드로는 조합 폭발을 못 피한다.
 * 동적 WHERE 조립은 Specification(Criteria API)이 제자리 — AuditSpecifications 참고.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>,
        JpaSpecificationExecutor<AuditEvent> {

    /** 최신순 N건 — occurredAt 인덱스를 타고, 같은 시각 다건은 id로 순서를 안정화한다 */
    List<AuditEvent> findAllByOrderByOccurredAtDescIdDesc(Pageable pageable);
}
