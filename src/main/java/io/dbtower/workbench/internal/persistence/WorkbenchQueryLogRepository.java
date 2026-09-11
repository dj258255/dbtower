package io.dbtower.workbench.internal.persistence;

import io.dbtower.workbench.internal.domain.WorkbenchQueryLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkbenchQueryLogRepository extends JpaRepository<WorkbenchQueryLog, Long> {

    List<WorkbenchQueryLog> findByInstanceIdAndPrincipalOrderByOccurredAtDesc(Long instanceId, String principal,
                                                                             Pageable pageable);
}
