package io.dbtower.backup;

import io.dbtower.backup.internal.domain.BackupRun;
import io.dbtower.backup.internal.persistence.BackupRunRepository;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 백업 신선도 집계 (Phase D7) — BackupRun 이력에서 인스턴스별 "마지막 성공 백업"을 뽑아
 * 최신인지·복원되는지를 읽어낸다.
 *
 * 철저히 읽기·가시화만 한다: 대상 DB에 붙지 않고(메타 DB의 이력만 본다), 백업을 자동 실행하지도 않는다.
 * 백업 실행은 BackupService(기존 기능)의 몫이고, 이 서비스는 그 결과를 상시 비춰줄 뿐이다.
 * 그래서 대상이 죽어 있어도 신선도는 계산된다(오히려 그때가 백업 최신 여부가 가장 중요하다).
 */
@Service
public class BackupFreshnessService {

    private final RegistryService registryService;
    private final BackupRunRepository runRepository;
    private final int freshnessHours;

    public BackupFreshnessService(RegistryService registryService, BackupRunRepository runRepository,
                                  @Value("${dbtower.backup.freshness-hours:24}") int freshnessHours) {
        this.registryService = registryService;
        this.runRepository = runRepository;
        this.freshnessHours = freshnessHours;
    }

    /** 전 인스턴스 신선도 — 웹 카드·GET /api/backup-freshness가 부른다. */
    public BackupFreshnessReport reportAll() {
        LocalDateTime now = LocalDateTime.now();
        List<BackupFreshness> all = registryService.findAll().stream()
                .map(instance -> freshnessFor(instance, now))
                .toList();
        return BackupFreshnessReport.of(now, freshnessHours, all);
    }

    /** 인스턴스 하나의 신선도 — GET /api/instances/{id}/backup-freshness가 부른다(존재 검증 포함). */
    public BackupFreshness freshnessFor(Long instanceId) {
        return freshnessFor(registryService.findById(instanceId), LocalDateTime.now());
    }

    /** 인스턴스 하나의 신선도 — 폴러(OpsAlertDetector)가 이미 들고 있는 인스턴스로 직접 부른다. */
    public BackupFreshness freshnessFor(DatabaseInstance instance) {
        return freshnessFor(instance, LocalDateTime.now());
    }

    /**
     * 마지막 성공 백업을 뽑아 신선도를 판정한다.
     * 성공 이력이 없으면 NO_BACKUP(사각지대), 있으면 경과가 임계 이내면 FRESH·초과면 STALE.
     * verifyStatus는 그 마지막 성공 백업의 복원 검증 결과(A7)를 그대로 실어 나른다.
     */
    BackupFreshness freshnessFor(DatabaseInstance instance, LocalDateTime now) {
        BackupRun latest = runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(instance.getId()).stream()
                .filter(r -> r.getStatus() == BackupRun.Status.SUCCESS)
                .findFirst()
                .orElse(null);

        if (latest == null) {
            return new BackupFreshness(instance.getId(), instance.getName(), instance.getType(),
                    null, null, null, null, false, BackupFreshness.Status.NO_BACKUP, freshnessHours);
        }

        LocalDateTime lastBackupAt = latest.getStartedAt();
        double elapsedHours = Duration.between(lastBackupAt, now).toMinutes() / 60.0;
        boolean fresh = elapsedHours <= freshnessHours;
        BackupFreshness.Status status = fresh ? BackupFreshness.Status.FRESH : BackupFreshness.Status.STALE;
        return new BackupFreshness(instance.getId(), instance.getName(), instance.getType(),
                lastBackupAt, latest.getVerifyStatus(), latest.getRemoteLocation(),
                elapsedHours, fresh, status, freshnessHours);
    }
}
