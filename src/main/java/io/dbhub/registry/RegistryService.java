package io.dbhub.registry;

import io.dbhub.operator.DbmsOperatorFactory;
import io.dbhub.operator.HealthStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/** 이기종 DB 인스턴스 등록·조회. 등록 시 접속 검증을 함께 수행한다. */
@Service
public class RegistryService {

    private final DatabaseInstanceRepository repository;
    private final DbmsOperatorFactory operatorFactory;

    public RegistryService(DatabaseInstanceRepository repository, DbmsOperatorFactory operatorFactory) {
        this.repository = repository;
        this.operatorFactory = operatorFactory;
    }

    /** 등록 전에 실제로 붙어보고, 붙지 않으면 등록을 거부한다 — 죽은 인스턴스가 레지스트리에 쌓이는 것 방지 */
    public DatabaseInstance register(DatabaseInstance instance) {
        HealthStatus health = operatorFactory.create(instance).health();
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
        return operatorFactory.create(findById(id)).health();
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }
}
