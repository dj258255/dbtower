package io.dbtower.insight.internal.job;

import io.dbtower.insight.AshSample;
import io.dbtower.insight.AshSampleTick;
import io.dbtower.insight.internal.AshSampleWriter;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 샘플의 기록 시각은 틱 시작이 아니라 그 인스턴스를 실제로 조회한 시각이다(#98).
 *
 * <p>워커 하나에 인스턴스 둘 — 앞 인스턴스 조회가 800ms 걸리면 뒤 인스턴스는 그만큼 늦게 묻는다. 전에는 둘 다 틱 시작 시각이
 * 기록돼 뒤 인스턴스의 기록이 실제 관측보다 800ms 앞섰다.
 */
class AshSamplerObservedTimeTest {

    @Test
    void 뒤에_조회한_인스턴스는_실제로_물은_시각이_기록된다() {
        DatabaseInstance slow = instance(1L, "slow");
        DatabaseInstance late = instance(2L, "late");
        RegistryService registry = mock(RegistryService.class);
        when(registry.findAll()).thenReturn(List.of(slow, late));
        Map<Long, LocalDateTime> askedAt = new ConcurrentHashMap<>();
        DbmsOperatorFactory factory = mock(DbmsOperatorFactory.class);
        for (DatabaseInstance inst : List.of(slow, late)) {
            DbmsOperator op = mock(DbmsOperator.class);
            when(op.activeSessions(anyInt())).thenAnswer(a -> {
                askedAt.put(inst.getId(), LocalDateTime.now());
                if (inst == slow) Thread.sleep(800);
                return List.of(new SessionInfo(10, "app", "active", null, null, "select 1", 5));
            });
            when(factory.create(inst)).thenReturn(op);
        }
        AshSampleWriter writer = mock(AshSampleWriter.class);
        LockProvider locks = mock(LockProvider.class);
        when(locks.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));

        LocalDateTime tickStart = LocalDateTime.now();
        new AshSamplerJob(registry, factory, writer, locks, 500, 1, 7).sample();

        ArgumentCaptor<AshSampleTick> ticks = ArgumentCaptor.forClass(AshSampleTick.class);
        verify(writer, times(2)).saveTick(ticks.capture());
        AshSampleTick lateTick = ticks.getAllValues().stream().filter(t -> t.instanceId() == 2L).findFirst().orElseThrow();
        long behindTick = Duration.between(tickStart, lateTick.sampledAt()).toMillis();
        long offFromAsked = Math.abs(Duration.between(askedAt.get(2L), lateTick.sampledAt()).toMillis());
        System.out.printf("MEASURE 뒤 인스턴스 기록 시각: 틱 시작 뒤 %dms, 실제 조회와 차이 %dms%n", behindTick, offFromAsked);
        assertThat(behindTick).as("기록 시각이 틱 시작에 머물렀다").isGreaterThanOrEqualTo(700);
        assertThat(offFromAsked).as("기록 시각이 실제 조회 시각과 어긋난다").isLessThan(50);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AshSample>> samples = ArgumentCaptor.forClass(List.class);
        verify(writer, times(2)).saveSamples(samples.capture());
        samples.getAllValues().stream().flatMap(List::stream).filter(s -> s.instanceId() == 2L)
                .forEach(s -> assertThat(s.sampledAt()).isEqualTo(lateTick.sampledAt()));
    }

    private static DatabaseInstance instance(long id, String name) {
        DatabaseInstance m = mock(DatabaseInstance.class);
        when(m.getId()).thenReturn(id);
        when(m.getName()).thenReturn(name);
        when(m.getType()).thenReturn(DbmsType.POSTGRESQL);
        when(m.isCollectionEnabled()).thenReturn(true);
        return m;
    }
}
