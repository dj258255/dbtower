package io.dbtower.backup.internal.persistence;

import io.dbtower.backup.internal.domain.BackupPolicyEntity;
import io.dbtower.operator.model.BackupPolicy.BackupType;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BackupPolicyRepository extends JpaRepository<BackupPolicyEntity, Long> {

    /** 정책 키는 (인스턴스, 타입) — FULL 앵커와 LOG 체인이 각자의 주기로 병행한다(V23) */
    Optional<BackupPolicyEntity> findByInstanceIdAndType(Long instanceId, BackupType type);

    List<BackupPolicyEntity> findAllByInstanceId(Long instanceId);

    List<BackupPolicyEntity> findByEnabledTrue();
}
