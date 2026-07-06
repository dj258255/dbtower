package io.dbtower.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanSnapshotRepository extends JpaRepository<PlanSnapshot, Long> {

    Optional<PlanSnapshot> findTopByInstanceIdAndQueryIdOrderByCapturedAtDesc(Long instanceId, String queryId);

    List<PlanSnapshot> findTop50ByInstanceIdOrderByCapturedAtDesc(Long instanceId);
}
