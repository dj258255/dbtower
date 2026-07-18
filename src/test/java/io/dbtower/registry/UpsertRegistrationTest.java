package io.dbtower.registry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 멱등 등록(upsert) 검증 — IaC가 "생성하면 등록"을 재실행해도 중복이 아니라 갱신이어야 한다.
 * 이것이 Phase C 프로비저닝 연동(Ansible/K8s/Terraform)의 안전장치다.
 */
class UpsertRegistrationTest {

    private final DatabaseInstanceRepository repository = Mockito.mock(DatabaseInstanceRepository.class);
    private final InstanceOperations operations = Mockito.mock(InstanceOperations.class);
    private final org.springframework.context.ApplicationEventPublisher events =
            Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
    private final RegistryService service = new RegistryService(repository, operations, events);

    @Test
    void 같은_이름이_없으면_새로_등록한다() {
        when(repository.findByName("prod-orders")).thenReturn(java.util.Optional.empty());
        when(operations.health(any())).thenReturn(HealthStatus.up("v1", 1));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DatabaseInstance result = service.upsert("prod-orders", DbmsType.MYSQL,
                "10.0.0.1", 3306, "orders", "monitor", "pw", false, null, null, null, null, null, null);

        assertEquals("prod-orders", result.getName());
        verify(operations).health(any());
        verify(repository).save(any());
    }

    @Test
    void 같은_이름이_있으면_접속정보를_갱신한다() {
        DatabaseInstance existing = new DatabaseInstance("prod-orders", DbmsType.MYSQL,
                "10.0.0.1", 3306, "orders", "monitor", "old-pw");
        when(repository.findByName("prod-orders")).thenReturn(java.util.Optional.of(existing));
        when(operations.health(any())).thenReturn(HealthStatus.up("v1", 1));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 같은 이름으로 host가 바뀐 재등록(예: 페일오버로 새 엔드포인트)
        DatabaseInstance result = service.upsert("prod-orders", DbmsType.MYSQL,
                "10.0.0.2", 3306, "orders", "monitor", "new-pw", false, "db-core", "https://grafana.example/d/1", null, null, null, null);

        assertEquals("10.0.0.2", result.getHost());
        assertEquals("new-pw", result.getPassword());
        // 접속 정보가 바뀌었으니 기존 풀은 정리돼야 한다
        verify(operations).release(any());
        verify(repository, never()).save(argThat(i -> i != existing)); // 새 엔티티가 아니라 기존 것을 갱신
    }

    @Test
    void 환경_리전_클러스터_태그가_보존된다() {
        when(repository.findByName("prod-orders")).thenReturn(java.util.Optional.empty());
        when(operations.health(any())).thenReturn(HealthStatus.up("v1", 1));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DatabaseInstance result = service.upsert("prod-orders", DbmsType.MYSQL,
                "10.0.0.1", 3306, "orders", "monitor", "pw", false, null, null, null,
                "prod", "ap-northeast-2", "orders-cluster");

        assertEquals("prod", result.getEnvironment());
        assertEquals("ap-northeast-2", result.getRegion());
        assertEquals("orders-cluster", result.getClusterLabel());
    }

    @Test
    void 갱신_대상이_접속에_실패하면_거부한다() {
        DatabaseInstance existing = new DatabaseInstance("prod-orders", DbmsType.MYSQL,
                "10.0.0.1", 3306, "orders", "monitor", "old-pw");
        when(repository.findByName("prod-orders")).thenReturn(java.util.Optional.of(existing));
        when(operations.health(any())).thenReturn(HealthStatus.down("접속 불가"));

        assertThrows(IllegalArgumentException.class, () -> service.upsert("prod-orders",
                DbmsType.MYSQL, "10.0.0.9", 3306, "orders", "monitor", "pw", false, null, null, null, null, null, null));
        verify(repository, never()).save(any());
    }
}
