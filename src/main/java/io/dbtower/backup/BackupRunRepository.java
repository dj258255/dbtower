package io.dbtower.backup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BackupRunRepository extends JpaRepository<BackupRun, Long> {

    List<BackupRun> findTop20ByInstanceIdOrderByStartedAtDesc(Long instanceId);
}
