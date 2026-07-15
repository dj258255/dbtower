package io.dbtower.backup.internal.persistence;

import io.dbtower.backup.internal.domain.BackupRun;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BackupRunRepository extends JpaRepository<BackupRun, Long> {

    List<BackupRun> findTop20ByInstanceIdOrderByStartedAtDesc(Long instanceId);
}
