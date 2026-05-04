package io.dbtower.backup;

import io.dbtower.operator.BackupPolicy;
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

    public record BackupRunView(String startedAt, long durationMs, String status, String detail) {
        static BackupRunView from(BackupRun r) {
            return new BackupRunView(r.getStartedAt().toString(), r.getDurationMs(),
                    r.getStatus().name(), r.getDetail());
        }
    }
}
