package io.dbtower.score;

import io.dbtower.advisor.AdvisorService;
import io.dbtower.advisor.InstanceAdvisorReport;
import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.insight.BaselineService;
import io.dbtower.insight.BaselineService.AnomalyScan;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.HealthStatus;
import io.dbtower.registry.RegistryService;
import io.dbtower.score.SignalContribution.Signal;
import io.dbtower.score.SignalContribution.State;
import io.dbtower.slo.SloReport;
import io.dbtower.slo.SloService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 스코어 종합 서비스 검증 (Phase D8) — 신호 수집 격리와 "데이터 부족 vs 나쁨" 구분, 나쁜 순 정렬을 고정한다.
 * 신호 하나가 예외로 터져도 나머지로 점수가 나오고, 없는 SLO를 장애로 오판하지 않는지 못박는다.
 */
class ScoreServiceTest {

    private final RegistryService registryService = Mockito.mock(RegistryService.class);
    private final BaselineService baselineService = Mockito.mock(BaselineService.class);
    private final AdvisorService advisorService = Mockito.mock(AdvisorService.class);
    private final SloService sloService = Mockito.mock(SloService.class);
    private final BackupFreshnessService freshnessService = Mockito.mock(BackupFreshnessService.class);

    private ScoreService service;
    private final LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        // 가중치는 기본값(ScoreWeights.defaults와 동일)으로 주입
        service = new ScoreService(registryService, baselineService, advisorService, sloService, freshnessService,
                45, 4, 16, 8, 3, 30, 25, 10, 20, 12);
    }

    private DatabaseInstance instance(long id, String name) {
        DatabaseInstance i = new DatabaseInstance(name, DbmsType.MYSQL, "h", 3306, "d", "u", "p");
        ReflectionTestUtils.setField(i, "id", id);
        return i;
    }

    private void stubHealthy(long id, DatabaseInstance inst) {
        when(registryService.health(id)).thenReturn(HealthStatus.up("8.0", 2));
        when(baselineService.detectAnomalies(eq(id), any()))
                .thenReturn(new AnomalyScan(id, 1, 10, 5, 3.0, List.of(), 0));
        when(advisorService.inspect(inst))
                .thenReturn(new InstanceAdvisorReport(id, inst.getName(), DbmsType.MYSQL, now, List.of(), 0, 0, 0));
        when(sloService.evaluate(id))
                .thenReturn(new SloReport(id, inst.getName(), DbmsType.MYSQL, now, null, null, null, SloReport.MEETING));
        when(freshnessService.freshnessFor(inst)).thenReturn(
                new BackupFreshness(id, inst.getName(), DbmsType.MYSQL, null, null, null, 2.0, true,
                        BackupFreshness.Status.FRESH, 24));
    }

    @Test
    void 신호_하나가_예외여도_나머지로_점수를_낸다() {
        DatabaseInstance inst = instance(1L, "mysql");
        stubHealthy(1L, inst);
        // Advisor 수집이 터진다(권한 부족 등)
        when(advisorService.inspect(inst)).thenThrow(new RuntimeException("권한 부족"));

        HealthScore s = service.evaluate(inst, now);

        assertEquals(100, s.score());        // 나머지 신호는 모두 정상 → 감점 0
        assertTrue(s.partial());             // 부분 데이터 표기
        assertEquals(4, s.countedSignals()); // Advisor만 제외
        SignalContribution advisor = s.contributions().stream()
                .filter(c -> c.signal() == Signal.ADVISOR).findFirst().orElseThrow();
        assertEquals(State.ERROR, advisor.state());
    }

    @Test
    void health_프로브가_예외로_실패하면_데이터부족이_아니라_다운으로_판정한다() {
        // 접속 거부 등으로 health()가 던지면(오퍼레이터가 풀 초기화 예외를 흘리는 경우) ERROR가 아니라 down이어야
        // 나쁜 순 정렬 최상단에 온다 — "닿지 않는다"를 "판단 보류"로 물러서지 않는다.
        DatabaseInstance inst = instance(1L, "dead");
        stubHealthy(1L, inst);
        when(registryService.health(1L)).thenThrow(new RuntimeException("Connection refused"));

        HealthScore s = service.evaluate(inst, now);

        assertTrue(s.down());
        SignalContribution health = s.contributions().stream()
                .filter(c -> c.signal() == Signal.HEALTH).findFirst().orElseThrow();
        assertEquals(State.PENALIZED, health.state());
        assertEquals(45.0, health.penalty());
        assertEquals(55, s.score()); // 100 - 45(다운). partial 아님(health는 다운으로 계산에 포함)
        assertFalse(s.partial());
    }

    @Test
    void 데이터_부족과_나쁨을_구분한다() {
        DatabaseInstance inst = instance(1L, "mysql");
        stubHealthy(1L, inst);
        // SLO는 표본 부족(데이터 부족 → 감점 없음), 백업은 없음(실제 위험 → 감점 20)
        when(sloService.evaluate(1L))
                .thenReturn(new SloReport(1L, "mysql", DbmsType.MYSQL, now, null, null, null, SloReport.INSUFFICIENT_DATA));
        when(freshnessService.freshnessFor(inst)).thenReturn(
                new BackupFreshness(1L, "mysql", DbmsType.MYSQL, null, null, null, null, false,
                        BackupFreshness.Status.NO_BACKUP, 24));

        HealthScore s = service.evaluate(inst, now);

        assertEquals(80, s.score());   // 백업 없음 20만 감점, SLO 부족은 제외
        assertTrue(s.partial());
        SignalContribution slo = s.contributions().stream()
                .filter(c -> c.signal() == Signal.SLO).findFirst().orElseThrow();
        SignalContribution backup = s.contributions().stream()
                .filter(c -> c.signal() == Signal.BACKUP).findFirst().orElseThrow();
        assertEquals(State.INSUFFICIENT_DATA, slo.state()); // 없는 SLO는 장애 아님
        assertEquals(State.PENALIZED, backup.state());      // 백업 없음은 나쁨
    }

    @Test
    void reportAll은_나쁜순으로_정렬한다() {
        DatabaseInstance dead = instance(1L, "dead");
        DatabaseInstance ok = instance(2L, "ok");
        stubHealthy(2L, ok);
        // 죽은 인스턴스: health down, 나머지 신호도 채워 예외 없이 계산되게
        when(registryService.health(1L)).thenReturn(HealthStatus.down("접속 실패"));
        when(baselineService.detectAnomalies(eq(1L), any()))
                .thenReturn(new AnomalyScan(1L, 1, 10, 5, 3.0, List.of(), 0));
        when(advisorService.inspect(dead))
                .thenReturn(new InstanceAdvisorReport(1L, "dead", DbmsType.MYSQL, now, List.of(), 0, 0, 0));
        when(sloService.evaluate(1L))
                .thenReturn(new SloReport(1L, "dead", DbmsType.MYSQL, now, null, null, null, SloReport.INSUFFICIENT_DATA));
        when(freshnessService.freshnessFor(dead)).thenReturn(
                new BackupFreshness(1L, "dead", DbmsType.MYSQL, null, null, null, null, false,
                        BackupFreshness.Status.NO_BACKUP, 24));
        // 입력 순서는 건강한 것 먼저 — 정렬이 뒤집는지 본다
        when(registryService.findAll()).thenReturn(List.of(ok, dead));

        HealthScoreReport report = service.reportAll();

        assertEquals(2, report.total());
        assertEquals(1L, report.instances().get(0).instanceId()); // 죽은 것이 최상단
        assertTrue(report.instances().get(0).down());
        assertEquals(2L, report.instances().get(1).instanceId());
        assertEquals("A", report.instances().get(1).grade());
    }
}
