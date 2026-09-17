package io.dbtower.insight.internal.job;

import io.dbtower.insight.CollectionStatus;
import io.dbtower.insight.internal.CollectionStatusStore;
import io.dbtower.insight.internal.SnapshotWriter;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.OperatorException;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 수집 결과 기록(#72) — 어디서 실패했는지를 남기고, 기록이 실패해도 수집은 계속한다 */
class SnapshotSchedulerStatusTest {

    private final RegistryService registry = mock(RegistryService.class);
    private final SnapshotWriter writer = mock(SnapshotWriter.class);
    private final CollectionStatusStore store = mock(CollectionStatusStore.class);
    private final DbmsOperatorFactory factory = mock(DbmsOperatorFactory.class);
    private final LockProvider locks = mock(LockProvider.class);
    private final DbmsOperator operator = mock(DbmsOperator.class);
    private SnapshotScheduler scheduler;

    @BeforeEach
    void setUp() {
        DatabaseInstance instance = mock(DatabaseInstance.class);
        when(instance.getId()).thenReturn(1L);
        when(instance.getName()).thenReturn("live-postgres");
        when(instance.isCollectionEnabled()).thenReturn(true);
        when(registry.findAll()).thenReturn(List.of(instance));
        when(factory.create(any())).thenReturn(operator);
        when(locks.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        when(operator.queryStats(anyInt())).thenReturn(List.of());
        scheduler = new SnapshotScheduler(registry, writer, store, factory, locks, 1, 1);
    }

    @Test
    void 저장에서_실패하면_STORE로_남긴다() {
        // #70 — 대상은 응답했는데 query_text 길이 초과로 배치 INSERT가 실패했다
        doThrow(new DataIntegrityViolationException("value too long")).when(writer).saveBatch(anyList());
        scheduler.collect();
        verify(store).recordFailure(eq(1L), any(), eq(CollectionStatus.STORE));
        verify(store, never()).recordSuccess(anyLong(), any());
    }

    @Test
    void 대상_조회에서_실패하면_TARGET으로_남긴다() {
        when(operator.queryStats(anyInt())).thenThrow(new OperatorException("connection refused"));
        scheduler.collect();
        verify(store).recordFailure(eq(1L), any(), eq(CollectionStatus.TARGET));
        verifyNoInteractions(writer);
    }

    @Test
    void 성공하면_성공을_남기고_기록이_실패해도_예외가_새지_않는다() {
        doThrow(new RuntimeException("meta db down")).when(store).recordSuccess(anyLong(), any());
        scheduler.collect();
        verify(writer).saveBatch(anyList());
        verify(store).recordSuccess(eq(1L), any());
    }
}
