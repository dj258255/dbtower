package io.dbtower.backup.internal.web;

import io.dbtower.backup.internal.BackupService;
import io.dbtower.backup.internal.domain.BackupPolicyEntity;
import io.dbtower.backup.internal.domain.BackupRun;

import io.dbtower.operator.model.BackupPolicy.BackupType;
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
                                BackupType type,
                                Boolean enabled) {
    }

    public record PolicyResponse(Long instanceId, int intervalMinutes,
                                 BackupType type, boolean enabled, String lastRunAt) {
        static PolicyResponse from(BackupPolicyEntity p) {
            return new PolicyResponse(p.getInstanceId(), p.getIntervalMinutes(), p.getType(),
                    p.isEnabled(), p.getLastRunAt() == null ? null : p.getLastRunAt().toString());
        }
    }

    /**
     * "N분 주기 백업해줘" — 추상 정책 등록. 실행 방식은 기종별 Operator가 결정.
     * 타입별 upsert(V23) — FULL 6시간 + LOG 15분처럼 병행 스케줄이 정석이라 타입이 정책 키의 일부다.
     * type 생략 시 FULL(기존 호출 호환).
     */
    @PutMapping("/backup-policy")
    public PolicyResponse upsert(@PathVariable Long id, @RequestBody PolicyRequest req) {
        BackupType type = req.type() == null ? BackupType.FULL : req.type();
        boolean enabled = req.enabled() == null || req.enabled();
        return PolicyResponse.from(backupService.upsertPolicy(id, req.intervalMinutes(), type, enabled));
    }

    /** 인스턴스의 정책 목록 — 타입별 병행 정책을 한눈에 */
    @GetMapping("/backup-policy")
    public List<PolicyResponse> policies(@PathVariable Long id) {
        return backupService.policiesFor(id).stream().map(PolicyResponse::from).toList();
    }

    /** 즉시 백업 실행 */
    @PostMapping("/backup")
    public BackupRunView runNow(@PathVariable Long id,
                                @RequestParam(defaultValue = "FULL") BackupType type) {
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
