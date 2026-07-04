package io.dbtower.alert;

import io.dbtower.insight.QuerySnapshotRepository;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.ReplicationState;
import io.dbtower.operator.SessionInfo;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 운영 감지 3규칙(장기 유휴 트랜잭션·복제 지연·스냅샷 정지)과 쿨다운 검증.
 * 임계 바로 아래는 조용하고 넘으면 울린다 — 노이즈 억제 계약을 고정한다.
 * 기존 operator 메서드(activeSessions·replicationState)를 mock으로 주입해 판정만 검증한다.
 */
class OpsAlertDetectorTest {

    private final DatabaseInstanceRepository instanceRepository = Mockito.mock(DatabaseInstanceRepository.class);
    private final DbmsOperatorFactory operatorFactory = Mockito.mock(DbmsOperatorFactory.class);
    private final QuerySnapshotRepository snapshotRepository = Mockito.mock(QuerySnapshotRepository.class);
    private final WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);

    private OpsAlertDetector detector;

    @BeforeEach
    void setUp() {
        // idle-txn 5s, replication-lag 30s, snapshot-stall 10m, cooldown 30m
        detector = new OpsAlertDetector(instanceRepository, operatorFactory, snapshotRepository, notifier,
                5, 30, 10, 30);
        DatabaseInstance instance = new DatabaseInstance(
                "test-db", DbmsType.POSTGRESQL, "127.0.0.1", 5432, "sample", "postgres", "pw");
        when(instanceRepository.findAll()).thenReturn(List.of(instance));
        when(operatorFactory.create(any())).thenReturn(operator);
        // 기본은 조용한 상태 — 각 테스트가 필요한 신호만 켠다
        when(operator.activeSessions(anyInt())).thenReturn(List.of());
        when(operator.replicationState()).thenReturn(new ReplicationState("STANDALONE", 0, "복제 구성 없음"));
        // createdAt이 지금(스냅샷 창 안)이라 스냅샷 정지 판정은 기본 보류 → 오탐 없음
    }

    private SessionInfo idleTxn(long pid, double elapsedMs) {
        return new SessionInfo(pid, "app", "idle in transaction", null, null, "SELECT 1", elapsedMs);
    }

    private String notifiedMessage() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void 장기_유휴_트랜잭션은_임계_초과일_때만_알린다() {
        // 임계(5s=5000ms) 이하 — 조용
        when(operator.activeSessions(anyInt())).thenReturn(List.of(idleTxn(101, 5000)));
        detector.detect();
        verify(notifier, never()).send(anyString());

        // 임계 초과 — 알림
        when(operator.activeSessions(anyInt())).thenReturn(List.of(idleTxn(102, 8000)));
        detector.detect();
        String message = notifiedMessage();
        assertTrue(message.contains("장기 유휴 트랜잭션"));
        assertTrue(message.contains("pid=102"));
    }

    @Test
    void active_상태_세션은_유휴_트랜잭션으로_보지_않는다() {
        when(operator.activeSessions(anyInt())).thenReturn(List.of(
                new SessionInfo(200, "app", "active", null, null, "UPDATE t SET x=1", 999_999)));
        detector.detect();
        verify(notifier, never()).send(anyString());
    }

    @Test
    void 복제_지연은_임계_초과일_때만_알리고_STANDALONE은_스킵한다() {
        // STANDALONE(기본 스텁) — 조용
        detector.detect();
        verify(notifier, never()).send(anyString());

        // 임계(30s) 이하 — 조용
        when(operator.replicationState()).thenReturn(new ReplicationState("REPLICA", 30, "recovery"));
        detector.detect();
        verify(notifier, never()).send(anyString());

        // 임계 초과 — 알림
        when(operator.replicationState()).thenReturn(new ReplicationState("REPLICA", 120, "recovery"));
        detector.detect();
        assertTrue(notifiedMessage().contains("복제 지연"));
    }

    @Test
    void 복제_lag_음수는_미지원_신호라_스킵한다() {
        when(operator.replicationState()).thenReturn(new ReplicationState("REPLICA", -1, "미지원"));
        detector.detect();
        verify(notifier, never()).send(anyString());
    }

    @Test
    void 같은_신호는_쿨다운_동안_다시_알리지_않는다() {
        when(operator.activeSessions(anyInt())).thenReturn(List.of(idleTxn(101, 8000)));
        detector.detect();
        detector.detect(); // 쿨다운 30분 안의 재감지
        verify(notifier, times(1)).send(anyString());
    }

    @Test
    void 감지_중_예외는_조용히_넘어간다() {
        when(operator.activeSessions(anyInt())).thenThrow(new RuntimeException("접속 실패"));
        assertDoesNotThrow(detector::detect);
        verify(notifier, never()).send(anyString());
    }
}
