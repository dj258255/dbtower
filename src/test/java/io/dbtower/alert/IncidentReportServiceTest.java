package io.dbtower.alert;

import io.dbtower.alert.internal.ConfigDriftService;
import io.dbtower.alert.internal.IncidentReportService;
import io.dbtower.alert.internal.IncidentReportService.IncidentReport;
import io.dbtower.alert.internal.persistence.PlanSnapshotRepository;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.ComparisonService.CompareResult;
import io.dbtower.insight.ComparisonService.WindowSummary;
import io.dbtower.insight.WaitEventHistoryService;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import io.dbtower.slo.SloService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 인시던트 리포트 조립 검증 — 구간 상한(24h) 절단·재구성 한계 명시·모든 절 포함.
 * 재료는 전부 mock(신규 수집 없이 기존 신호 조합임을 그대로 반영).
 */
class IncidentReportServiceTest {

    private final RegistryService registry = Mockito.mock(RegistryService.class);
    private final ComparisonService comparison = Mockito.mock(ComparisonService.class);
    private final ConfigDriftService config = Mockito.mock(ConfigDriftService.class);
    private final PlanSnapshotRepository plans = Mockito.mock(PlanSnapshotRepository.class);
    private final WaitEventHistoryService waits = Mockito.mock(WaitEventHistoryService.class);
    private final SloService slo = Mockito.mock(SloService.class);
    private final AiAnalyzer ai = Mockito.mock(AiAnalyzer.class);

    private final IncidentReportService service = new IncidentReportService(
            registry, comparison, config, plans, waits, slo, ai);

    private final DatabaseInstance instance = Mockito.mock(DatabaseInstance.class);

    private void stub() {
        when(instance.getName()).thenReturn("prod-db");
        when(instance.getType()).thenReturn(DbmsType.POSTGRESQL);
        when(registry.findById(1L)).thenReturn(instance);
        WindowSummary w = new WindowSummary(100, 50.0, 0.5, 200, 3);
        when(comparison.compare(anyLong(), any(), any(), any(), any()))
                .thenReturn(new CompareResult(w, w, 10.0, 20.0, 5.0, 1, List.of()));
        when(config.changesInWindow(anyLong(), any(), any())).thenReturn(List.of());
        when(plans.findTop50ByInstanceIdOrderByCapturedAtDesc(anyLong())).thenReturn(List.of());
        when(waits.inWindow(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        when(slo.healthInWindow(anyLong(), any(), any())).thenReturn(List.of());
        when(ai.complete(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void 모든_절과_재구성_한계를_담는다() {
        stub();
        LocalDateTime from = LocalDateTime.of(2026, 7, 18, 3, 0);
        IncidentReport report = service.generate(1L, from, from.plusHours(2));
        String md = report.markdown();
        assertTrue(md.contains("# 인시던트 리포트"));
        assertTrue(md.contains("## 성능 비교"));
        assertTrue(md.contains("## 설정 변경"));
        assertTrue(md.contains("## 플랜 플립"));
        assertTrue(md.contains("## 대기 이벤트"));
        assertTrue(md.contains("## 가용성"));
        // 재구성 한계(감지 알림 미포함) 정직 표기
        assertTrue(md.contains("영속 이력이 없어"));
    }

    @Test
    void 구간이_24시간을_넘으면_잘리고_노트를_남긴다() {
        stub();
        LocalDateTime from = LocalDateTime.of(2026, 7, 18, 0, 0);
        IncidentReport report = service.generate(1L, from, from.plusHours(48));
        assertTrue(report.truncationNotes().stream().anyMatch(n -> n.contains("24시간")));
    }

    @Test
    void LBAC_스코프_밖이면_findById가_막는다() {
        when(registry.findById(9L)).thenThrow(new RuntimeException("404"));
        assertThrows(RuntimeException.class,
                () -> service.generate(9L, LocalDateTime.now().minusHours(1), LocalDateTime.now()));
    }
}
