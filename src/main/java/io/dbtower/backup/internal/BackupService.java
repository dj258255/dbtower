package io.dbtower.backup.internal;

import io.dbtower.backup.internal.domain.BackupPolicyEntity;
import io.dbtower.backup.internal.domain.BackupRun;
import io.dbtower.backup.internal.persistence.BackupPolicyRepository;
import io.dbtower.backup.internal.persistence.BackupRunRepository;

import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.RestoreVerification;
import io.dbtower.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final BackupPolicyRepository policyRepository;
    private final BackupRunRepository runRepository;
    private final RemoteBackupStore remoteStore;

    public BackupService(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                         BackupPolicyRepository policyRepository, BackupRunRepository runRepository,
                         RemoteBackupStore remoteStore) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.policyRepository = policyRepository;
        this.runRepository = runRepository;
        this.remoteStore = remoteStore;
    }

    /** 정책 등록/수정 — 인스턴스당 하나 */
    public BackupPolicyEntity upsertPolicy(Long instanceId, int intervalMinutes,
                                           BackupPolicy.BackupType type, boolean enabled) {
        registryService.findById(instanceId); // 존재 검증
        BackupPolicyEntity policy = policyRepository.findByInstanceId(instanceId)
                .orElseGet(() -> new BackupPolicyEntity(instanceId, intervalMinutes, type));
        policy.update(intervalMinutes, type, enabled);
        return policyRepository.save(policy);
    }

    /** 즉시 실행 — 실행 방식은 Operator가 기종에 맞게 결정하고, 성공/실패/미지원을 이력으로 남긴다 */
    public BackupRun runNow(Long instanceId, BackupPolicy.BackupType type) {
        LocalDateTime startedAt = LocalDateTime.now();
        long start = System.currentTimeMillis();
        BackupRun run;
        try {
            BackupResult result = operatorFactory.create(registryService.findById(instanceId))
                    .backup(new BackupPolicy(null, type));
            run = new BackupRun(instanceId, startedAt, System.currentTimeMillis() - start,
                    BackupRun.Status.SUCCESS, result.location() + " (" + result.bytes() + " bytes)",
                    result.location());
            // 3-2-1의 오프사이트 — 업로드 실패는 백업 실패가 아니다(로컬 성공은 유효, 위치만 비움)
            remoteStore.upload(instanceId, result.location()).ifPresent(run::recordRemote);
        } catch (UnsupportedOperationException e) {
            // "기종이 못 하는 것"은 실패가 아니다 — 사유와 함께 UNSUPPORTED로 구분 기록(위장 금지)
            run = new BackupRun(instanceId, startedAt, System.currentTimeMillis() - start,
                    BackupRun.Status.UNSUPPORTED, e.getMessage());
            log.info("백업 미지원 instance={} type={} 사유={}", instanceId, type, e.getMessage());
        } catch (Exception e) {
            run = new BackupRun(instanceId, startedAt, System.currentTimeMillis() - start,
                    BackupRun.Status.FAILED, e.getMessage());
            log.warn("백업 실패 instance={} cause={}", instanceId, e.getMessage());
        }
        run.setBackupType(type.name());   // PITR 범위 계산의 전제(V13) — 어떤 타입의 실행이었는지 기록
        return runRepository.save(run);
    }

    /** 복원 검증 결과 — 어떤 산출물을 검증했는지와 3-값 결과를 함께 돌려준다 */
    public record VerifyOutcome(String location, RestoreVerification verification) {
    }

    /**
     * 백업의 복원 가능성 검증 (A7). location을 주면 그 산출물을, 없으면 가장 최근 성공 백업을 검증한다.
     * 결과는 응답으로 돌려주고, 검증 대상이 이력 한 건이면 그 행에 verify_status도 남긴다.
     * "테스트해 본 적 없는 백업은 백업이 아니다" — 여기서 실제로 임시 대상에 복원해 본다.
     */
    public VerifyOutcome verifyLatest(Long instanceId, String explicitLocation) {
        var instance = registryService.findById(instanceId); // 존재 검증
        BackupRun target = null;
        String location = explicitLocation;
        if (location == null || location.isBlank()) {
            target = runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(instanceId).stream()
                    .filter(r -> r.getStatus() == BackupRun.Status.SUCCESS)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "검증할 성공한 백업 이력이 없습니다 — 먼저 백업을 실행하세요"));
            // location 컬럼이 있으면 그대로, 없는(구) 행은 detail에서 위치만 복원
            location = target.getLocation() != null ? target.getLocation()
                    : locationFromDetail(target.getDetail());
        }
        RestoreVerification verification = operatorFactory.create(instance).verifyRestore(location);
        if (target != null) {
            target.recordVerification(verification.status().name(), LocalDateTime.now());
            runRepository.save(target);
        }
        return new VerifyOutcome(location, verification);
    }

    /** 구 이력의 detail은 "location (N bytes)" 꼴 — 뒤의 크기 주석을 떼어 위치만 복원한다 */
    private static String locationFromDetail(String detail) {
        return detail == null ? null : detail.replaceFirst("\\s*\\(-?\\d+ bytes\\)\\s*$", "");
    }

    public List<BackupPolicyEntity> duePolicies(LocalDateTime now) {
        return policyRepository.findByEnabledTrue().stream().filter(p -> p.isDue(now)).toList();
    }

    public void markRun(BackupPolicyEntity policy, LocalDateTime at) {
        policy.markRun(at);
        policyRepository.save(policy);
    }

    public List<BackupRun> history(Long instanceId) {
        return runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(instanceId);
    }

    /**
     * PITR 복원 가능 창 (Phase 2) — "마지막 성공 FULL 시각 ~ 그 이후 마지막 성공 LOG 시각".
     * FULL이 없으면 복원 불가, LOG가 없으면 FULL 시점으로만 복원 가능(창이 점 하나)임을 정직하게 표기.
     * restoreGuide는 기종별 명령 문안(Operator가 생성) — 실행은 사람이 한다.
     */
    public record PitrWindow(boolean available, LocalDateTime fullAt, String fullLocation,
                             LocalDateTime lastLogAt, int logCount, String note, String restoreGuide) {
    }

    public PitrWindow pitrWindow(Long instanceId, String targetTime) {
        var instance = registryService.findById(instanceId);
        var full = runRepository.findTopByInstanceIdAndBackupTypeAndStatusOrderByStartedAtDesc(
                instanceId, BackupPolicy.BackupType.FULL.name(), BackupRun.Status.SUCCESS);
        if (full.isEmpty()) {
            return new PitrWindow(false, null, null, null, 0,
                    "성공한 FULL 백업이 없어 시점 복구 불가 — 먼저 FULL 백업을 실행하세요"
                            + " (타입 기록은 V13부터라 이전 이력은 계산에서 제외)", null);
        }
        List<BackupRun> logs = runRepository
                .findByInstanceIdAndBackupTypeAndStatusAndStartedAtAfterOrderByStartedAtAsc(
                        instanceId, BackupPolicy.BackupType.LOG.name(), BackupRun.Status.SUCCESS,
                        full.get().getStartedAt());
        LocalDateTime lastLogAt = logs.isEmpty() ? null : logs.get(logs.size() - 1).getStartedAt();
        String note = logs.isEmpty()
                ? "FULL 이후 LOG 백업이 없어 FULL 시점으로만 복원 가능(임의 시점 불가)"
                : "FULL(%s) ~ 마지막 LOG(%s) 사이의 임의 시점으로 복원 가능".formatted(
                        full.get().getStartedAt(), lastLogAt);
        // 안내 목표 시점: 지정 없으면 창의 끝(마지막 LOG 또는 FULL 시각)
        String target = (targetTime != null && !targetTime.isBlank()) ? targetTime
                : String.valueOf(lastLogAt != null ? lastLogAt : full.get().getStartedAt());
        String guide = operatorFactory.create(instance).pitrRestoreGuide(
                full.get().getLocation(),
                logs.stream().map(BackupRun::getLocation).toList(),
                target);
        return new PitrWindow(true, full.get().getStartedAt(), full.get().getLocation(),
                lastLogAt, logs.size(), note, guide);
    }
}
