package io.dbtower.backup.internal.persistence;

import io.dbtower.backup.internal.domain.BackupRun;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BackupRunRepository extends JpaRepository<BackupRun, Long> {

    List<BackupRun> findTop20ByInstanceIdOrderByStartedAtDesc(Long instanceId);

    /** PITR 앵커 — 가장 최근의 성공한 FULL(backupType='FULL', V13 이전 무타입 행은 제외) */
    Optional<BackupRun> findTopByInstanceIdAndBackupTypeAndStatusOrderByStartedAtDesc(
            Long instanceId, String backupType, BackupRun.Status status);

    /** PITR 로그 체인 — 앵커(FULL) 이후의 성공한 LOG들을 시간순으로 */
    List<BackupRun> findByInstanceIdAndBackupTypeAndStatusAndStartedAtAfterOrderByStartedAtAsc(
            Long instanceId, String backupType, BackupRun.Status status, LocalDateTime after);
}
