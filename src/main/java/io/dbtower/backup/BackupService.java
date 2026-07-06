package io.dbtower.backup;

import io.dbtower.operator.BackupPolicy;
import io.dbtower.operator.BackupResult;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.RestoreVerification;
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

    /** 즉시 실행 — 실행 방식은 Operator가 기종에 맞게 결정하고, 성공/실패를 이력으로 남긴다 */
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
        } catch (Exception e) {
            run = new BackupRun(instanceId, startedAt, System.currentTimeMillis() - start,
                    BackupRun.Status.FAILED, e.getMessage());
            log.warn("백업 실패 instance={} cause={}", instanceId, e.getMessage());
        }
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
}
