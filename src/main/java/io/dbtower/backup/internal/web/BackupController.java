package io.dbtower.backup.internal.web;

import io.dbtower.backup.internal.BackupService;
import io.dbtower.backup.internal.domain.BackupPolicyEntity;
import io.dbtower.backup.internal.domain.BackupRun;

import io.dbtower.operator.model.BackupPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/instances/{id}")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    public record PolicyRequest(@Min(1) @Max(10080) int intervalMinutes,
                                BackupPolicy.BackupType type,
                                Boolean enabled) {
    }

    public record PolicyResponse(Long instanceId, int intervalMinutes,
                                 BackupPolicy.BackupType type, boolean enabled, String lastRunAt) {
        static PolicyResponse from(BackupPolicyEntity p) {
            return new PolicyResponse(p.getInstanceId(), p.getIntervalMinutes(), p.getType(),
                    p.isEnabled(), p.getLastRunAt() == null ? null : p.getLastRunAt().toString());
        }
    }

    /** "N분 주기 백업해줘" — 추상 정책 등록. 실행 방식은 기종별 Operator가 결정 */
    @PutMapping("/backup-policy")
    public PolicyResponse upsert(@PathVariable Long id, @RequestBody PolicyRequest req) {
        BackupPolicy.BackupType type = req.type() == null ? BackupPolicy.BackupType.FULL : req.type();
        boolean enabled = req.enabled() == null || req.enabled();
        return PolicyResponse.from(backupService.upsertPolicy(id, req.intervalMinutes(), type, enabled));
    }

    /** 즉시 백업 실행 */
    @PostMapping("/backup")
    public BackupRunView runNow(@PathVariable Long id,
                                @RequestParam(defaultValue = "FULL") BackupPolicy.BackupType type) {
        return BackupRunView.from(backupService.runNow(id, type));
    }

    @GetMapping("/backup-runs")
    public List<BackupRunView> history(@PathVariable Long id) {
        return backupService.history(id).stream().map(BackupRunView::from).toList();
    }

    /**
     * PITR 복원 가능 창 + 기종별 복원 명령 안내 (Phase 2) — 읽기 전용(안내문 생성만, 실행은 사람).
     * targetTime(선택, ISO)을 주면 그 시점 기준의 안내문을, 없으면 창의 끝 시점 기준.
     */
    @GetMapping("/pitr-window")
    public BackupService.PitrWindow pitrWindow(@PathVariable Long id,
                                               @RequestParam(required = false) String targetTime) {
        return backupService.pitrWindow(id, targetTime);
    }

    /**
     * 백업 복원 검증 (A7) — 가장 최근 성공 백업을, location을 주면 그 산출물을 검증한다.
     * status는 VERIFIED/FAILED/UNSUPPORTED, restoredObjectCount는 실제 복원까지 한 경우에만 채워진다.
     */
    @PostMapping("/backup/verify")
    public VerifyResponse verify(@PathVariable Long id,
                                 @RequestParam(required = false) String location) {
        BackupService.VerifyOutcome outcome = backupService.verifyLatest(id, location);
        return VerifyResponse.from(outcome);
    }

    public record VerifyResponse(String status, String detail, Integer restoredObjectCount, String location) {
        static VerifyResponse from(BackupService.VerifyOutcome o) {
            return new VerifyResponse(o.verification().status().name(), o.verification().detail(),
                    o.verification().restoredObjectCount(), o.location());
        }
    }

    public record BackupRunView(String startedAt, long durationMs, String status, String detail,
                                String verifyStatus, String remoteLocation, String backupType) {
        static BackupRunView from(BackupRun r) {
            return new BackupRunView(r.getStartedAt().toString(), r.getDurationMs(),
                    r.getStatus().name(), r.getDetail(), r.getVerifyStatus(), r.getRemoteLocation(),
                    r.getBackupType());
        }
    }
}
