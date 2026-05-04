package io.dbtower.backup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BackupPolicyRepository extends JpaRepository<BackupPolicyEntity, Long> {

    Optional<BackupPolicyEntity> findByInstanceId(Long instanceId);

    List<BackupPolicyEntity> findByEnabledTrue();
}
