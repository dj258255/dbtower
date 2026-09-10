package io.dbtower.workbench.internal.persistence;

import io.dbtower.workbench.internal.domain.Worksheet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorksheetRepository extends JpaRepository<Worksheet, Long> {

    List<Worksheet> findByPrincipalAndInstanceIdAndArchivedFalseOrderByUpdatedAtDesc(String principal, Long instanceId);

    long countByPrincipalAndInstanceId(String principal, Long instanceId);
}
