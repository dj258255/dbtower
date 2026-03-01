package io.dbhub.insight;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface QuerySnapshotRepository extends JpaRepository<QuerySnapshot, Long> {

    List<QuerySnapshot> findByInstanceIdAndCapturedAtBetweenOrderByCapturedAt(
            Long instanceId, LocalDateTime from, LocalDateTime to);
}
