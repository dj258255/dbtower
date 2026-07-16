package io.dbtower.alert;

import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.alert.internal.job.OpsAlertDetector;
import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.insight.QuerySnapshotRepository;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.InstanceDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * B-1 인메모리 정리 — 인스턴스가 삭제되면 OpsAlertDetector가 폴 사이에 들고 있던 상태
 * (쿨다운 lastAlerted·데드락 카운터 lastDeadlockCount·데드락 시그 lastDeadlockSig)가 비워져야 한다.
 * 상태가 남아 있는지를 "삭제 후 판정이 초기화되는가"로 간접 검증한다(맵은 private이라 직접 관찰 대신 행위로).
 */
class OpsAlertDetectorEvictTest {

    private final DatabaseInstanceRepository instanceRepository = Mockito.mock(DatabaseInstanceRepository.class);
    private final DbmsOperatorFactory operatorFactory = Mockito.mock(DbmsOperatorFactory.class);
    private final QuerySnapshotRepository snapshotRepository = Mockito.mock(QuerySnapshotRepository.class);
    private final BackupFreshnessService backupFreshnessService = Mockito.mock(BackupFreshnessService.class);
    private final WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);

    private OpsAlertDetector detector;

    @BeforeEach
    void setUp() {
        // idle-txn 5s, replication-lag 30s, snapshot-stall 10m, cooldown 30m
        detector = new OpsAlertDetector(instanceRepository, operatorFactory, snapshotRepository,
                backupFreshnessService, notifier, 5, 30, 10, 30, 1024);

        // 데드락 dedup·evict는 instanceId 키를 쓰므로 id 있는 인스턴스로 검증한다.
        DatabaseInstance withId = Mockito.mock(DatabaseInstance.class);
        when(withId.getId()).thenReturn(1L);
        when(withId.getName()).thenReturn("test-db");
        when(withId.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(withId.isCollectionEnabled()).thenReturn(true);
        when(instanceRepository.findAll()).thenReturn(List.of(withId));

        when(operatorFactory.create(any())).thenReturn(operator);
        when(operator.activeSessions(anyInt())).thenReturn(List.of());
        when(operator.replicationState()).thenReturn(new io.dbtower.operator.model.ReplicationState("STANDALONE", 0, "없음"));
        when(operator.deadlockCount()).thenReturn(Optional.empty());
        when(backupFreshnessService.freshnessFor(any(DatabaseInstance.class))).thenReturn(fresh());
    }

    private BackupFreshness fresh() {
        return new BackupFreshness(1L, "test-db", DbmsType.POSTGRESQL,
                LocalDateTime.now().minusHours(1), "VERIFIED", null, 1.0, true,
                BackupFreshness.Status.FRESH, 24);
    }

    private SessionInfo idleTxn(long pid, double elapsedMs) {
        return new SessionInfo(pid, "app", "idle in transaction", null, null, "SELECT 1", elapsedMs);
    }

    @Test
    void 삭제_이벤트는_쿨다운_상태를_비워_같은_신호가_다시_울리게_한다() {
        when(operator.activeSessions(anyInt())).thenReturn(List.of(idleTxn(101, 8000)));

        detector.detect();                 // 최초 알림 → send 1
        detector.detect();                 // 쿨다운(30m) 안이라 조용
        verify(notifier, times(1)).sendEmbed(anyString(), any());

        detector.onInstanceDeleted(new InstanceDeletedEvent(1L)); // 쿨다운 키 제거

        detector.detect();                 // 쿨다운이 비워졌으므로 다시 울린다 → send 2
        verify(notifier, times(2)).sendEmbed(anyString(), any());
    }

    @Test
    void 삭제_이벤트는_데드락_카운터_기준선을_비운다() {
        when(operator.deadlockCount()).thenReturn(Optional.of(5L));
        detector.detect(); // 첫 관측 = 기준선 저장(알림 없음)

        detector.onInstanceDeleted(new InstanceDeletedEvent(1L)); // lastDeadlockCount 제거

        // 기준선이 사라졌으므로 8은 다시 "첫 관측"으로 취급 → 델타 없음 → 알림 없음.
        // (evict가 없었다면 5→8 델타로 "데드락 발생 +3"을 보냈을 것이다.)
        when(operator.deadlockCount()).thenReturn(Optional.of(8L));
        detector.detect();
        verify(notifier, never()).sendEmbed(anyString(), any());
    }
}
