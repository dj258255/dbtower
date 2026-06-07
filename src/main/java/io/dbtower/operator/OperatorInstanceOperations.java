package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.HealthStatus;
import io.dbtower.registry.InstanceOperations;
import org.springframework.stereotype.Component;

/** registry 모듈이 선언한 연결 능력의 실제 구현 — 기종 분기는 여기서도 팩토리 한 곳뿐이다 */
@Component
public class OperatorInstanceOperations implements InstanceOperations {

    private final DbmsOperatorFactory factory;
    private final ConnectionPools pools;
    private final MongoClientCache mongoClients;

    public OperatorInstanceOperations(DbmsOperatorFactory factory, ConnectionPools pools,
                                      MongoClientCache mongoClients) {
        this.factory = factory;
        this.pools = pools;
        this.mongoClients = mongoClients;
    }

    @Override
    public HealthStatus health(DatabaseInstance instance) {
        return factory.create(instance).health();
    }

    @Override
    public void release(Long instanceId) {
        pools.close(instanceId);
        mongoClients.close(instanceId);
    }
}
