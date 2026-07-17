package io.dbtower.backup.internal;

import io.dbtower.backup.internal.domain.BackupPolicyEntity;
import io.dbtower.backup.internal.domain.BackupRun;
import io.dbtower.backup.internal.persistence.BackupPolicyRepository;
import io.dbtower.backup.internal.persistence.BackupRunRepository;

import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.operator.model.BackupPolicy.BackupType;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.BackupArtifactCipher;
import io.dbtower.operator.model.RestoreVerification;
import io.dbtower.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final BackupArtifactCipher artifactCipher;

    public BackupService(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                         BackupPolicyRepository policyRepository, BackupRunRepository runRepository,
                         RemoteBackupStore remoteStore, BackupArtifactCipher artifactCipher) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.policyRepository = policyRepository;
        this.runRepository = runRepository;
        this.remoteStore = remoteStore;
        this.artifactCipher = artifactCipher;
    }

    /** 정책 등록/수정 — 키는 (인스턴스, 타입). FULL 앵커와 LOG 체인이 각자의 주기로 병행한다(V23) */
    public BackupPolicyEntity upsertPolicy(Long instanceId, int intervalMinutes,
                                           BackupType type, boolean enabled) {
        registryService.findById(instanceId); // 존재 검증
        BackupPolicyEntity policy = policyRepository.findByInstanceIdAndType(instanceId, type)
                .orElseGet(() -> new BackupPolicyEntity(instanceId, intervalMinutes, type));
        policy.update(intervalMinutes, type, enabled);
        return policyRepository.save(policy);
    }

    /** 인스턴스의 정책 목록 — 타입별로 최대 3개(FULL/LOG/PHYSICAL) */
    public List<BackupPolicyEntity> policiesFor(Long instanceId) {
        registryService.findById(instanceId); // 존재 검증(스코프 밖 404 동일 경계)
        return policyRepository.findAllByInstanceId(instanceId);
    }

    /** 즉시 실행 — 실행 방식은 Operator가 기종에 맞게 결정하고, 성공/실패/미지원을 이력으로 남긴다 */
    public BackupRun runNow(Long instanceId, BackupType type) {
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
        // 산출물 암호화(3-2-1-1-0) — 암호문이면 임시 평문으로 풀어 기존 검증 경로에 그대로 전달하고,
        // 사용 즉시 지운다(평문 수명 최소화). GCM 태그 검증이 복호에 포함되므로 변조 산출물은 여기서
        // 명확히 실패한다 — "복원해 본 백업"에 "변조 안 된 백업"까지 얹는 셈.
        RestoreVerification verification;
        Path artifact = Path.of(location);
        if (artifactCipher.isEncrypted(artifact)) {
            Path plain = null;
            try {
                plain = artifactCipher.decryptToTemp(artifact);
                verification = operatorFactory.create(instance).verifyRestore(plain.toString());
            } catch (IOException e) {
                verification = RestoreVerification.failed("산출물 복호 실패(키 불일치·변조 의심): " + e.getMessage());
            } finally {
                if (plain != null) {
                    try {
                        Files.deleteIfExists(plain);
                    } catch (IOException ignored) {
                        // 임시 파일 삭제 실패는 검증 결과를 바꾸지 않는다 — OS tmp 정리에 맡긴다
                    }
                }
            }
        } else {
            verification = operatorFactory.create(instance).verifyRestore(location);
        }
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
        // 앵커 = 가장 최근의 성공한 전체 백업 — PHYSICAL(물리)이 있으면 그것이 진짜 앵커다
        // (PG·Oracle은 논리 덤프에 로그를 재생할 수 없다). 둘 다 있으면 더 최근 것.
        var physical = runRepository.findTopByInstanceIdAndBackupTypeAndStatusOrderByStartedAtDesc(
                instanceId, BackupType.PHYSICAL.name(), BackupRun.Status.SUCCESS);
        var logical = runRepository.findTopByInstanceIdAndBackupTypeAndStatusOrderByStartedAtDesc(
                instanceId, BackupType.FULL.name(), BackupRun.Status.SUCCESS);
        var full = physical.isEmpty() ? logical
                : logical.isEmpty() ? physical
                : (physical.get().getStartedAt().isAfter(logical.get().getStartedAt()) ? physical : logical);
        if (full.isEmpty()) {
            return new PitrWindow(false, null, null, null, 0,
                    "성공한 전체(FULL/PHYSICAL) 백업이 없어 시점 복구 불가 — 먼저 전체 백업을 실행하세요"
                            + " (타입 기록은 V13부터라 이전 이력은 계산에서 제외)", null);
        }
        List<BackupRun> logs = runRepository
                .findByInstanceIdAndBackupTypeAndStatusAndStartedAtAfterOrderByStartedAtAsc(
                        instanceId, BackupType.LOG.name(), BackupRun.Status.SUCCESS,
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
