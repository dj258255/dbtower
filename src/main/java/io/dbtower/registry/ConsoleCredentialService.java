package io.dbtower.registry;

import io.dbtower.registry.internal.domain.InstanceCredential;
import io.dbtower.registry.internal.persistence.InstanceCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 워크벤치 콘솔 계정 관리. 조회·변경 계정을 모니터 계정과 분리해 보관하고, 저장 전에 실제로 붙어 본다.
 * 팀 범위는 모든 진입에서 {@link RegistryService#findById}가 지킨다(범위 밖이면 미등록과 같은 404).
 */
@Service
public class ConsoleCredentialService {

    private final InstanceCredentialRepository repository;
    private final RegistryService registry;
    private final InstanceOperations operations;

    public ConsoleCredentialService(InstanceCredentialRepository repository, RegistryService registry,
                                    InstanceOperations operations) {
        this.repository = repository;
        this.registry = registry;
        this.operations = operations;
    }

    /** 콘솔 실행용 자격증명. 미설정이면 empty — 호출자는 이를 "기능 꺼짐"으로 다룬다(fail-closed). */
    public Optional<ConsoleCredential> find(Long instanceId, CredentialPurpose purpose) {
        registry.findById(instanceId);
        return repository.findByInstanceIdAndPurpose(instanceId, purpose)
                .map(c -> new ConsoleCredential(c.getUsername(), c.getPassword()));
    }

    public List<CredentialSummary> summaries(Long instanceId) {
        registry.findById(instanceId);
        return repository.findByInstanceIdOrderByPurposeAsc(instanceId).stream()
                .map(ConsoleCredentialService::summary)
                .toList();
    }

    @Transactional
    public CredentialSummary save(Long instanceId, CredentialPurpose purpose, String username, String password) {
        DatabaseInstance instance = registry.findById(instanceId);
        if (username.startsWith("vault:")) {
            throw new IllegalArgumentException("콘솔 계정은 아직 Vault 동적 자격증명을 지원하지 않습니다");
        }
        if (username.equals(instance.getUsername())) {
            // 같은 계정이면 사람의 조회·변경 권한이 수집기 경로로, 수집기의 권한이 사람 경로로 섞인다
            throw new IllegalArgumentException("콘솔 계정은 모니터 계정과 달라야 합니다");
        }
        // 등록과 같은 fail-closed: 저장 전에 실제로 붙어 본다. id 없는 사본이라 풀을 만들지 않는 1회성 접속이다
        DatabaseInstance probe = new DatabaseInstance(instance.getName(), instance.getType(), instance.getHost(),
                instance.getPort(), instance.getDbName(), username, password, instance.isUseTls());
        HealthStatus health = operations.health(probe);
        if (!health.up()) {
            throw new IllegalArgumentException("접속 실패로 저장 거부: " + health.message());
        }
        InstanceCredential credential = repository.findByInstanceIdAndPurpose(instanceId, purpose)
                .map(existing -> {
                    existing.replace(username, password);
                    return existing;
                })
                .orElseGet(() -> new InstanceCredential(instanceId, purpose, username, password));
        return summary(repository.save(credential));
    }

    @Transactional
    public void delete(Long instanceId, CredentialPurpose purpose) {
        registry.findById(instanceId);
        repository.findByInstanceIdAndPurpose(instanceId, purpose).ifPresent(repository::delete);
    }

    private static CredentialSummary summary(InstanceCredential c) {
        return new CredentialSummary(c.getPurpose(), c.getUsername(), c.getUpdatedAt());
    }
}
