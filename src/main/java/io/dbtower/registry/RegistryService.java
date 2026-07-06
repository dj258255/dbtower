package io.dbtower.registry;

import org.springframework.stereotype.Service;

import java.util.List;

/** 이기종 DB 인스턴스 등록·조회. 등록 시 접속 검증을 함께 수행한다. */
@Service
public class RegistryService {

    private final DatabaseInstanceRepository repository;
    private final InstanceOperations operations;

    public RegistryService(DatabaseInstanceRepository repository, InstanceOperations operations) {
        this.repository = repository;
        this.operations = operations;
    }

    /** 등록 전에 실제로 붙어보고, 붙지 않으면 등록을 거부한다 — 죽은 인스턴스가 레지스트리에 쌓이는 것 방지 */
    public DatabaseInstance register(DatabaseInstance instance) {
        HealthStatus health = operations.health(instance);
        if (!health.up()) {
            throw new IllegalArgumentException("접속 실패로 등록 거부: " + health.message());
        }
        return repository.save(instance);
    }

    /**
     * 멱등 등록(upsert) — IaC(Ansible/K8s/Terraform)가 프로비저닝 후 등록을 재실행해도
     * 중복이 아니라 갱신이 되게 한다. 같은 이름이 있으면 접속 정보를 덮고, 없으면 새로 등록.
     * 어느 경로든 등록 전에 실제 접속을 검증한다(register와 동일한 fail-closed).
     */
    public DatabaseInstance upsert(String name, DbmsType type, String host, int port,
                                   String dbName, String username, String password, boolean useTls) {
        DatabaseInstance existing = repository.findByName(name).orElse(null);
        if (existing == null) {
            return register(new DatabaseInstance(name, type, host, port, dbName, username, password, useTls));
        }
        existing.updateConnection(type, host, port, dbName, username, password, useTls);
        HealthStatus health = operations.health(existing);
        if (!health.up()) {
            throw new IllegalArgumentException("접속 실패로 갱신 거부: " + health.message());
        }
        // 접속 정보가 바뀌었으니 기존 커넥션 풀은 정리 — 다음 조회 때 새 정보로 다시 연다
        operations.release(existing.getId());
        return repository.save(existing);
    }

    public List<DatabaseInstance> findAll() {
        return repository.findAll();
    }

    public DatabaseInstance findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 인스턴스: " + id));
    }

    public HealthStatus health(Long id) {
        return operations.health(findById(id));
    }

    public void delete(Long id) {
        repository.deleteById(id);
        operations.release(id);
    }
}
