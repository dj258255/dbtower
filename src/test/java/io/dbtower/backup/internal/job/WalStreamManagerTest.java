package io.dbtower.backup.internal.job;

import io.dbtower.backup.internal.domain.BackupPolicyEntity;
import io.dbtower.backup.internal.persistence.BackupPolicyRepository;
import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * WAL 스트리밍 대상 판정 (Phase 2 잔여) — "누구를 스트리밍하나"의 계약 고정.
 * PG 기종 + LOG 정책 enabled + 수집 격리 아님, 세 조건이 전부 참일 때만 상주 스트림 대상이다.
 * (프로세스 생존·재시작은 라이브 e2e — VERIFICATION 84절)
 */
class WalStreamManagerTest {

    private final DatabaseInstanceRepository instances = Mockito.mock(DatabaseInstanceRepository.class);
    private final BackupPolicyRepository policies = Mockito.mock(BackupPolicyRepository.class);

    private DatabaseInstance instance(long id, DbmsType type, boolean collecting) {
        DatabaseInstance m = Mockito.mock(DatabaseInstance.class);
        when(m.getId()).thenReturn(id);
        when(m.getType()).thenReturn(type);
        when(m.isCollectionEnabled()).thenReturn(collecting);
        return m;
    }

    private BackupPolicyEntity policy(long instanceId, BackupPolicy.BackupType type) {
        return new BackupPolicyEntity(instanceId, 60, type);
    }

    @Test
    void PG_LOG_정책만_스트리밍_대상이다() {
        DatabaseInstance pg = instance(1, DbmsType.POSTGRESQL, true);
        DatabaseInstance mysql = instance(2, DbmsType.MYSQL, true);
        DatabaseInstance pgIsolated = instance(3, DbmsType.POSTGRESQL, false);
        DatabaseInstance pgFullOnly = instance(4, DbmsType.POSTGRESQL, true);
        when(policies.findByEnabledTrue()).thenReturn(List.of(
                policy(1, BackupPolicy.BackupType.LOG),      // 대상
                policy(2, BackupPolicy.BackupType.LOG),      // MySQL — 제외
                policy(3, BackupPolicy.BackupType.LOG),      // 격리 — 제외(문제 대상을 두드리지 않기)
                policy(4, BackupPolicy.BackupType.FULL)));   // LOG 아님 — 제외
        when(instances.findById(anyLong())).thenAnswer(inv -> switch (((Long) inv.getArgument(0)).intValue()) {
            case 1 -> Optional.of(pg);
            case 2 -> Optional.of(mysql);
            case 3 -> Optional.of(pgIsolated);
            case 4 -> Optional.of(pgFullOnly);
            default -> Optional.empty();
        });

        WalStreamManager manager = new WalStreamManager(instances, policies, "pg_receivewal ...");

        assertEquals(Set.of(1L), manager.desiredInstanceIds());
    }

    @Test
    void 명령_미설정이면_점검_자체가_조용히_끝난다() {
        // 기능 게이트 — 미설정 노드는 스트리밍 소유자가 아니다(다중 노드 슬롯 배타 전제)
        WalStreamManager manager = new WalStreamManager(instances, policies, "");
        assertDoesNotThrow(manager::ensureStreams);
        Mockito.verifyNoInteractions(policies);
    }
}
