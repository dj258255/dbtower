package io.dbtower.backup;

import io.dbtower.backup.internal.domain.BackupRun;
import io.dbtower.backup.internal.persistence.BackupRunRepository;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 백업 신선도 판정 검증 (Phase D7).
 *
 * 마지막 성공 백업으로 임계 이내(FRESH)·초과(STALE)·이력 없음(NO_BACKUP)을 정확히 가르는지,
 * 실패한 백업은 신선도 근거로 치지 않는지, 복원 검증 상태를 그대로 실어 나르는지 고정한다.
 * 임계는 24h로 둔다.
 */
class BackupFreshnessServiceTest {

    private final RegistryService registryService = Mockito.mock(RegistryService.class);
    private final BackupRunRepository runRepository = Mockito.mock(BackupRunRepository.class);

    private BackupFreshnessService service;
    private final DatabaseInstance instance = new DatabaseInstance(
            "mysql-prod", DbmsType.MYSQL, "127.0.0.1", 3306, "app", "root", "pw");

    @BeforeEach
    void setUp() {
        service = new BackupFreshnessService(registryService, runRepository, 24);
    }

    /** startedAt이 지정 시각인 성공 백업 이력 한 건(복원 검증 상태 포함) */
    private BackupRun success(LocalDateTime startedAt, String verifyStatus) {
        BackupRun run = new BackupRun(1L, startedAt, 1000, BackupRun.Status.SUCCESS, "/backups/x (10 bytes)", "/backups/x");
        if (verifyStatus != null) {
            run.recordVerification(verifyStatus, startedAt);
        }
        return run;
    }

    private BackupRun failed(LocalDateTime startedAt) {
        return new BackupRun(1L, startedAt, 500, BackupRun.Status.FAILED, "접속 실패");
    }

    @Test
    void 임계_이내_백업은_FRESH다() {
        LocalDateTime now = LocalDateTime.now();
        when(runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(any()))
                .thenReturn(List.of(success(now.minusHours(2), "VERIFIED")));

        BackupFreshness f = service.freshnessFor(instance, now);

        assertEquals(BackupFreshness.Status.FRESH, f.status());
        assertTrue(f.fresh());
        assertEquals("VERIFIED", f.verifyStatus());
        assertEquals(2.0, f.elapsedHours(), 0.01);
    }

    @Test
    void 임계_초과_백업은_STALE다() {
        LocalDateTime now = LocalDateTime.now();
        when(runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(any()))
                .thenReturn(List.of(success(now.minusHours(30), null)));

        BackupFreshness f = service.freshnessFor(instance, now);

        assertEquals(BackupFreshness.Status.STALE, f.status());
        assertFalse(f.fresh());
        assertEquals(30.0, f.elapsedHours(), 0.01);
    }

    @Test
    void 임계_정각은_아직_신선하다() {
        // 경계: 정확히 24h면 임계 이내(<=)로 본다
        LocalDateTime now = LocalDateTime.now();
        when(runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(any()))
                .thenReturn(List.of(success(now.minusHours(24), null)));

        assertEquals(BackupFreshness.Status.FRESH, service.freshnessFor(instance, now).status());
    }

    @Test
    void 성공_이력이_없으면_NO_BACKUP이다() {
        when(runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(any()))
                .thenReturn(List.of());

        BackupFreshness f = service.freshnessFor(instance, LocalDateTime.now());

        assertEquals(BackupFreshness.Status.NO_BACKUP, f.status());
        assertFalse(f.fresh());
        assertNull(f.lastBackupAt());
        assertNull(f.elapsedHours());
        assertNull(f.verifyStatus());
    }

    @Test
    void 실패한_백업만_있으면_NO_BACKUP이다() {
        LocalDateTime now = LocalDateTime.now();
        when(runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(any()))
                .thenReturn(List.of(failed(now.minusHours(1))));

        assertEquals(BackupFreshness.Status.NO_BACKUP, service.freshnessFor(instance, now).status());
    }

    @Test
    void 가장_최근_성공_백업을_기준으로_삼는다() {
        // 이력은 startedAt 내림차순 — 실패가 앞서도 그 뒤의 성공 백업을 마지막으로 인정한다
        LocalDateTime now = LocalDateTime.now();
        when(runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(any()))
                .thenReturn(List.of(
                        failed(now.minusHours(1)),
                        success(now.minusHours(3), "VERIFIED"),
                        success(now.minusHours(50), "FAILED")));

        BackupFreshness f = service.freshnessFor(instance, now);

        assertEquals(BackupFreshness.Status.FRESH, f.status());
        assertEquals(3.0, f.elapsedHours(), 0.01);
        assertEquals("VERIFIED", f.verifyStatus());
    }

    @Test
    void 요약은_나쁜_순으로_정렬하고_카운트한다() {
        // 정렬·카운트 계약(BackupFreshnessReport.of)만 고정한다 — 입력 순서와 무관하게 나쁜 순으로 나온다
        LocalDateTime now = LocalDateTime.now();
        BackupFreshnessReport report = BackupFreshnessReport.of(now, 24, List.of(
                new BackupFreshness(1L, "fresh", DbmsType.MYSQL, now.minusHours(1), "VERIFIED", null, 1.0, true,
                        BackupFreshness.Status.FRESH, 24),
                new BackupFreshness(2L, "stale", DbmsType.POSTGRESQL, now.minusHours(40), null, null, 40.0, false,
                        BackupFreshness.Status.STALE, 24),
                new BackupFreshness(3L, "none", DbmsType.MONGODB, null, null, null, null, false,
                        BackupFreshness.Status.NO_BACKUP, 24)));

        assertEquals(3, report.total());
        assertEquals(1, report.freshCount());
        assertEquals(1, report.staleCount());
        assertEquals(1, report.noBackupCount());
        // 나쁜 순: NO_BACKUP → STALE → FRESH
        assertEquals(BackupFreshness.Status.NO_BACKUP, report.instances().get(0).status());
        assertEquals(BackupFreshness.Status.STALE, report.instances().get(1).status());
        assertEquals(BackupFreshness.Status.FRESH, report.instances().get(2).status());
    }

    @Test
    void reportAll은_등록된_전_인스턴스를_집계한다() {
        DatabaseInstance a = instance;
        DatabaseInstance b = new DatabaseInstance("pg", DbmsType.POSTGRESQL, "h", 5432, "d", "u", "p");
        when(registryService.findAll()).thenReturn(List.of(a, b));
        when(runRepository.findTop20ByInstanceIdOrderByStartedAtDesc(any())).thenReturn(List.of());

        BackupFreshnessReport report = service.reportAll();

        assertEquals(2, report.total());
        assertEquals(2, report.noBackupCount());
        assertEquals(24, report.thresholdHours());
    }
}
