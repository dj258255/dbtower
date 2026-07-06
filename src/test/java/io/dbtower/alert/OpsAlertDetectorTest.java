package io.dbtower.alert;

import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
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
    private final BackupFreshnessService backupFreshnessService = Mockito.mock(BackupFreshnessService.class);
    private final WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);

    private DatabaseInstance instance;
    private OpsAlertDetector detector;

    @BeforeEach
    void setUp() {
        // idle-txn 5s, replication-lag 30s, snapshot-stall 10m, cooldown 30m
        detector = new OpsAlertDetector(instanceRepository, operatorFactory, snapshotRepository,
                backupFreshnessService, notifier, 5, 30, 10, 30);
        instance = new DatabaseInstance(
                "test-db", DbmsType.POSTGRESQL, "127.0.0.1", 5432, "sample", "postgres", "pw");
        when(instanceRepository.findAll()).thenReturn(List.of(instance));
        when(operatorFactory.create(any())).thenReturn(operator);
        // 기본은 조용한 상태 — 각 테스트가 필요한 신호만 켠다
        when(operator.activeSessions(anyInt())).thenReturn(List.of());
        when(operator.replicationState()).thenReturn(new ReplicationState("STANDALONE", 0, "복제 구성 없음"));
        // 백업은 기본으로 신선(FRESH) — 백업 신선도 규칙은 조용. 각 테스트가 필요할 때만 STALE/NO_BACKUP으로 켠다
        when(backupFreshnessService.freshnessFor(any(DatabaseInstance.class))).thenReturn(fresh());
        // createdAt이 지금(스냅샷 창 안)이라 스냅샷 정지 판정은 기본 보류 → 오탐 없음
    }

    private BackupFreshness fresh() {
        return new BackupFreshness(1L, "test-db", DbmsType.POSTGRESQL,
                java.time.LocalDateTime.now().minusHours(1), "VERIFIED", null, 1.0, true,
                BackupFreshness.Status.FRESH, 24);
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

    // ---------- 백업 신선도 (D7) ----------

    private BackupFreshness stale(double elapsedHours, String verifyStatus) {
        return new BackupFreshness(1L, "test-db", DbmsType.POSTGRESQL,
                java.time.LocalDateTime.now().minusHours((long) elapsedHours), verifyStatus, null,
                elapsedHours, false, BackupFreshness.Status.STALE, 24);
    }

    private BackupFreshness noBackup() {
        return new BackupFreshness(1L, "test-db", DbmsType.POSTGRESQL, null, null, null, null, false,
                BackupFreshness.Status.NO_BACKUP, 24);
    }

    @Test
    void 백업이_신선하면_조용하다() {
        // 기본 스텁이 FRESH — 다른 신호도 없음
        detector.detect();
        verify(notifier, never()).send(anyString());
    }

    @Test
    void 마지막_백업이_임계를_넘으면_알린다() {
        when(backupFreshnessService.freshnessFor(any(DatabaseInstance.class)))
                .thenReturn(stale(48, "VERIFIED"));
        detector.detect();
        String message = notifiedMessage();
        assertTrue(message.contains("백업 신선도 초과"));
        assertTrue(message.contains("임계 24h"));
    }

    @Test
    void 복원_검증_실패한_STALE_백업은_근거에_FAILED를_덧붙인다() {
        when(backupFreshnessService.freshnessFor(any(DatabaseInstance.class)))
                .thenReturn(stale(30, "FAILED"));
        detector.detect();
        assertTrue(notifiedMessage().contains("복원 검증 FAILED"));
    }

    @Test
    void 등록_직후_백업_없음은_아직_판단_보류한다() {
        // 기본 instance의 createdAt이 now(임계 창 안) — 첫 백업 전이라 오탐 방지로 조용
        when(backupFreshnessService.freshnessFor(any(DatabaseInstance.class))).thenReturn(noBackup());
        detector.detect();
        verify(notifier, never()).send(anyString());
    }

    @Test
    void 오래_등록됐는데_백업_없음이면_사각지대로_알린다() {
        // createdAt을 임계 창보다 오래되게 하려면 엔티티에 setter가 없어 mock으로 제어한다
        DatabaseInstance old = Mockito.mock(DatabaseInstance.class);
        when(old.getId()).thenReturn(1L);
        when(old.getName()).thenReturn("test-db");
        when(old.getCreatedAt()).thenReturn(java.time.LocalDateTime.now().minusDays(3));
        when(instanceRepository.findAll()).thenReturn(List.of(old));
        when(backupFreshnessService.freshnessFor(any(DatabaseInstance.class))).thenReturn(noBackup());
        detector.detect();
        assertTrue(notifiedMessage().contains("백업 없음"));
    }

    @Test
    void 백업_신선도_경보도_쿨다운_동안_다시_알리지_않는다() {
        when(backupFreshnessService.freshnessFor(any(DatabaseInstance.class)))
                .thenReturn(stale(48, "VERIFIED"));
        detector.detect();
        detector.detect(); // 쿨다운 30분 안의 재감지
        verify(notifier, times(1)).send(anyString());
    }

    @Test
    void 대상_접속이_죽어도_백업_신선도_경보는_계속_울린다() {
        // operator 기반 감지가 접속 실패로 죽어도(별도 try) 백업 경보는 살아 있어야 한다
        when(operator.activeSessions(anyInt())).thenThrow(new RuntimeException("접속 실패"));
        when(backupFreshnessService.freshnessFor(any(DatabaseInstance.class)))
                .thenReturn(stale(48, "VERIFIED"));
        detector.detect();
        assertTrue(notifiedMessage().contains("백업 신선도 초과"));
    }
}
