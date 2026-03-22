package io.dbhub.backup;

import io.dbhub.operator.BackupPolicy;
import io.dbhub.operator.BackupResult;
import io.dbhub.operator.DbmsOperatorFactory;
import io.dbhub.registry.RegistryService;
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

    public BackupService(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                         BackupPolicyRepository policyRepository, BackupRunRepository runRepository) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.policyRepository = policyRepository;
        this.runRepository = runRepository;
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
                    BackupRun.Status.SUCCESS, result.location() + " (" + result.bytes() + " bytes)");
        } catch (Exception e) {
            run = new BackupRun(instanceId, startedAt, System.currentTimeMillis() - start,
                    BackupRun.Status.FAILED, e.getMessage());
            log.warn("백업 실패 instance={} cause={}", instanceId, e.getMessage());
        }
        return runRepository.save(run);
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
