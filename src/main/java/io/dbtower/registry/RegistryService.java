package io.dbtower.registry;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/** 이기종 DB 인스턴스 등록·조회. 등록 시 접속 검증을 함께 수행한다. */
@Service
public class RegistryService {

    private final DatabaseInstanceRepository repository;
    private final InstanceOperations operations;
    private final ApplicationEventPublisher events;

    public RegistryService(DatabaseInstanceRepository repository, InstanceOperations operations,
                           ApplicationEventPublisher events) {
        this.repository = repository;
        this.operations = operations;
        this.events = events;
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

    /** 수집 활성/격리 토글 (Phase F) — 문제 인스턴스를 삭제하지 않고 관제에서 잠시 뺀다. */
    public DatabaseInstance setCollectionEnabled(Long id, boolean enabled) {
        DatabaseInstance instance = findById(id);
        instance.setCollectionEnabled(enabled);
        return repository.save(instance);
    }

    public void delete(Long id) {
        // 자식 행(query_snapshot·plan_snapshot·backup_run·backup_policy_entity·health_sample)은
        // FK ON DELETE CASCADE(V10)가 DB에서 함께 지운다 — 여기선 부모 한 행만 지운다.
        repository.deleteById(id);
        // 커넥션 풀·클라이언트 정리(대상 접속 자원).
        operations.release(id);
        // 폴러들이 폴 사이에 들고 있는 인메모리 상태(히스토그램·데드락 카운터/시그·쿨다운·백오프)를
        // 각 모듈이 @EventListener로 비우게 알린다 — DB CASCADE가 닿지 못하는 프로세스 메모리 정리(B-1).
        events.publishEvent(new InstanceDeletedEvent(id));
    }
}
