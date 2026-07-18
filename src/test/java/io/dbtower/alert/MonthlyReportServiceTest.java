package io.dbtower.alert;

import io.dbtower.advisor.AdvisorService;
import io.dbtower.advisor.InstanceAdvisorReport;
import io.dbtower.alert.internal.ConfigDriftService;
import io.dbtower.alert.internal.MonthlyReportService;
import io.dbtower.alert.internal.MonthlyReportService.MonthlyReport;
import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.finops.FinOpsQuery;
import io.dbtower.finops.WasteSummary;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import io.dbtower.score.HealthScoreView;
import io.dbtower.score.ScoreQuery;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 월간 점검 리포트 조립 검증 — 헬스·백업·Advisor·낭비·설정 변경 전 절 포함, 등급/점수 반영.
 * 재료는 전부 공개 API mock(신규 수집 없이 조합임을 반영).
 */
class MonthlyReportServiceTest {

    private final RegistryService registry = Mockito.mock(RegistryService.class);
    private final ScoreQuery score = Mockito.mock(ScoreQuery.class);
    private final AdvisorService advisor = Mockito.mock(AdvisorService.class);
    private final BackupFreshnessService freshness = Mockito.mock(BackupFreshnessService.class);
    private final FinOpsQuery finops = Mockito.mock(FinOpsQuery.class);
    private final ConfigDriftService config = Mockito.mock(ConfigDriftService.class);

    private final MonthlyReportService service = new MonthlyReportService(
            registry, score, advisor, freshness, finops, config);

    private final DatabaseInstance instance = Mockito.mock(DatabaseInstance.class);

    private void stub() {
        when(instance.getName()).thenReturn("prod-db");
        when(instance.getType()).thenReturn(DbmsType.POSTGRESQL);
        when(registry.findById(1L)).thenReturn(instance);
        when(score.scoreFor(1L)).thenReturn(new HealthScoreView(1L, 82, "B", false));
        when(freshness.freshnessFor(instance)).thenReturn(new BackupFreshness(
                1L, "prod-db", DbmsType.POSTGRESQL, LocalDateTime.now().minusHours(3), "VERIFIED",
                "s3://bak", 3.0, true, BackupFreshness.Status.FRESH, 24));
        when(advisor.inspect(instance)).thenReturn(
                InstanceAdvisorReport.of(1L, "prod-db", DbmsType.POSTGRESQL, LocalDateTime.now(), List.of()));
        when(finops.wasteSummary(1L)).thenReturn(new WasteSummary(1L, 4, true));
        when(config.changesInWindow(anyLong(), any(), any())).thenReturn(List.of());
    }

    @Test
    void 전_절과_점수를_담는다() {
        stub();
        LocalDateTime to = LocalDateTime.now();
        MonthlyReport r = service.generate(1L, to.minusDays(30), to);
        assertEquals(82, r.score());
        assertEquals("B", r.grade());
        String md = r.markdown();
        assertTrue(md.contains("# 월간 점검 리포트"));
        assertTrue(md.contains("## 헬스 스코어"));
        assertTrue(md.contains("82점 (B)"));
        assertTrue(md.contains("## 백업"));
        assertTrue(md.contains("FRESH"));
        assertTrue(md.contains("## Advisor 점검"));
        assertTrue(md.contains("## 낭비 신호"));
        assertTrue(md.contains("낭비 후보 4건"));
        assertTrue(md.contains("## 설정 변경"));
    }

    @Test
    void 낭비_미지원_기종은_판정불가로_표기한다() {
        stub();
        when(finops.wasteSummary(1L)).thenReturn(new WasteSummary(1L, 0, false));
        MonthlyReport r = service.generate(1L, LocalDateTime.now().minusDays(30), LocalDateTime.now());
        assertTrue(r.markdown().contains("미지원 기종"));
    }
}
