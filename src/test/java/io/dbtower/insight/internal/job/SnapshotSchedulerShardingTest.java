package io.dbtower.insight.internal.job;

import io.dbtower.insight.internal.SnapshotWriter;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 수집 샤딩 (Phase 4) — 샤드별 락을 각각 시도해 획득한 샤드의 인스턴스(id % N)만 수집하는 계약 고정.
 * 노드가 여럿이면 락 경쟁으로 샤드가 자연 분산되고, 락을 다 잡으면(단일 노드) 전 샤드를 수집한다 —
 * 페일오버가 설정 없이 유지되는 것이 이 설계의 핵심이다.
 */
class SnapshotSchedulerShardingTest {

    private final DatabaseInstanceRepository repository = Mockito.mock(DatabaseInstanceRepository.class);
    private final SnapshotWriter writer = Mockito.mock(SnapshotWriter.class);
    private final DbmsOperatorFactory operatorFactory = Mockito.mock(DbmsOperatorFactory.class);
    private final LockProvider lockProvider = Mockito.mock(LockProvider.class);
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);
    private final SimpleLock lock = Mockito.mock(SimpleLock.class);

    private DatabaseInstance instance(long id, String name) {
        DatabaseInstance m = Mockito.mock(DatabaseInstance.class);
        when(m.getId()).thenReturn(id);
        when(m.getName()).thenReturn(name);
        when(m.isCollectionEnabled()).thenReturn(true);
        return m;
    }

    private SnapshotScheduler scheduler(int shards) {
        // workers=1 — 지터 0으로 결정적 실행
        return new SnapshotScheduler(repository, writer, operatorFactory, lockProvider, 1, shards);
    }

    private Set<String> collectedNames() {
        ArgumentCaptor<DatabaseInstance> captor = ArgumentCaptor.forClass(DatabaseInstance.class);
        verify(operatorFactory, atLeast(0)).create(captor.capture());
        return captor.getAllValues().stream().map(DatabaseInstance::getName).collect(Collectors.toSet());
    }

    @Test
    void 획득한_샤드의_인스턴스만_수집한다() {
        List<DatabaseInstance> four = List.of(
                instance(1, "i1"), instance(2, "i2"), instance(3, "i3"), instance(4, "i4"));
        when(repository.findAll()).thenReturn(four);
        when(operatorFactory.create(any())).thenReturn(operator);
        when(operator.queryStats(anyInt())).thenReturn(List.of());
        // 샤드 0만 획득(1은 다른 노드가 보유 중)
        ArgumentCaptor<LockConfiguration> locks = ArgumentCaptor.forClass(LockConfiguration.class);
        when(lockProvider.lock(locks.capture())).thenAnswer(inv ->
                ((LockConfiguration) inv.getArgument(0)).getName().endsWith("-0")
                        ? Optional.of(lock) : Optional.empty());

        scheduler(2).collect();

        // id % 2 == 0 → i2, i4만 수집 — 다른 샤드는 그 락 보유자의 몫
        assertEquals(Set.of("i2", "i4"), collectedNames());
        assertEquals(List.of("snapshot-collect-0", "snapshot-collect-1"),
                locks.getAllValues().stream().map(LockConfiguration::getName).toList());
        verify(lock, times(1)).unlock();
    }

    @Test
    void 락을_전부_잡으면_전_샤드를_수집한다_페일오버() {
        List<DatabaseInstance> three = List.of(instance(1, "i1"), instance(2, "i2"), instance(3, "i3"));
        when(repository.findAll()).thenReturn(three);
        when(operatorFactory.create(any())).thenReturn(operator);
        when(operator.queryStats(anyInt())).thenReturn(List.of());
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));

        scheduler(2).collect();

        // 단일 생존 노드 = 전 샤드 인수 — 수집 공백 없음
        assertEquals(Set.of("i1", "i2", "i3"), collectedNames());
        verify(lock, times(2)).unlock();
    }

    @Test
    void 기본_샤드_1은_기존_락_이름을_그대로_쓴다() {
        // 롤링 배포 중 구버전 노드(어노테이션 락 "snapshot-collect")와도 같은 락을 다퉈야 한다
        List<DatabaseInstance> one = List.of(instance(1, "i1"));
        when(repository.findAll()).thenReturn(one);
        when(operatorFactory.create(any())).thenReturn(operator);
        when(operator.queryStats(anyInt())).thenReturn(List.of());
        ArgumentCaptor<LockConfiguration> locks = ArgumentCaptor.forClass(LockConfiguration.class);
        when(lockProvider.lock(locks.capture())).thenReturn(Optional.of(lock));

        scheduler(1).collect();

        assertEquals(List.of("snapshot-collect"),
                locks.getAllValues().stream().map(LockConfiguration::getName).toList());
        assertEquals(Set.of("i1"), collectedNames());
    }

    @Test
    void 락을_하나도_못_잡으면_아무것도_수집하지_않는다() {
        List<DatabaseInstance> one = List.of(instance(1, "i1"));
        when(repository.findAll()).thenReturn(one);
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        scheduler(2).collect();

        verify(operatorFactory, never()).create(any());
    }
}
