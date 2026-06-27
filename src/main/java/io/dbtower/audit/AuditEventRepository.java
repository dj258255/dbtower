package io.dbtower.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** 최신순 N건 — occurredAt 인덱스를 타고, 같은 시각 다건은 id로 순서를 안정화한다 */
    List<AuditEvent> findAllByOrderByOccurredAtDescIdDesc(Pageable pageable);
}
