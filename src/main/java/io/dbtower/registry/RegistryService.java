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
