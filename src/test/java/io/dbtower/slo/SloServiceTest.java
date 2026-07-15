package io.dbtower.slo;

import io.dbtower.slo.AvailabilitySli;
import io.dbtower.slo.ErrorBudget;
import io.dbtower.slo.LatencySli;
import io.dbtower.slo.internal.persistence.HealthSampleRepository;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.LatencyPercentile;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DB SLO / 에러 버짓 산식 검증 (Phase D4) — Google SRE·DBRE 모델.
 *
 * 레이턴시 SLI가 D4a source를 정직하게 유지(MySQL NATIVE p95·PG ESTIMATED)하고 UNSUPPORTED면 평균으로
 * 폴백하는지, 가용성 SLI·버짓 소진율·번인 레이트가 명시된 산식대로 계산되는지, 표본이 부족하면 판정을
 * 보류(데이터 부족)하는지를 고정한다. 설정은 target 99.5%·window 30일·번인창 60분·샘플간격 1분·p95 임계 100ms.
 */
class SloServiceTest {

    private final RegistryService registryService = Mockito.mock(RegistryService.class);
    private final DbmsOperatorFactory operatorFactory = Mockito.mock(DbmsOperatorFactory.class);
    private final HealthSampleRepository sampleRepository = Mockito.mock(HealthSampleRepository.class);

    // target 0.995, window 30일, 번인창 60분, minSamples 5, pollMs 60000(=간격 1분), p95 임계 100ms, limit 20
    private final SloService service = new SloService(registryService, operatorFactory, sampleRepository,
            0.995, 30, 60, 5, 60000L, 100.0, 20);

    private LatencyPercentile pct(String id, double p95, double p99, String source) {
        return new LatencyPercentile(id, "SELECT " + id, p95, p99, source);
    }

    private QueryStat stat(long calls, double totalMs) {
        return new QueryStat("q", "SELECT 1", calls, totalMs, 0);
    }

    // ---------- 레이턴시 SLI (D4a 재사용·폴백) ----------

    @Test
    void 레이턴시는_상위쿼리들_중_최악_p95를_SLI로_삼고_source를_유지한다() {
        // MySQL NATIVE 3개 — 최악 p95(120)이 임계 100 초과라 BREACHING, 넘는 쿼리 1개 카운트
        List<LatencyPercentile> rows = List.of(
                pct("a", 40, 60, LatencyPercentile.NATIVE),
                pct("b", 120, 200, LatencyPercentile.NATIVE),
                pct("c", 90, 110, LatencyPercentile.NATIVE));

        LatencySli sli = service.computeLatency(rows, List.of());

        assertEquals(LatencyPercentile.NATIVE, sli.source());
        assertEquals(120.0, sli.observedMs());
        assertEquals(120.0, sli.p95Ms());
        assertEquals("b", sli.coreQueryId());
        assertEquals(1, sli.breachingCoreQueries());
        assertEquals(3, sli.totalCoreQueries());
        assertEquals(LatencySli.BREACHING, sli.verdict());
    }

    @Test
    void 레이턴시_모든_쿼리가_임계_이내면_MEETING이다() {
        List<LatencyPercentile> rows = List.of(
                pct("a", 40, 60, LatencyPercentile.ESTIMATED),
                pct("b", 80, 95, LatencyPercentile.ESTIMATED));

        LatencySli sli = service.computeLatency(rows, List.of());

        assertEquals(LatencyPercentile.ESTIMATED, sli.source()); // PG 추정치도 실측인 척하지 않고 그대로 유지
        assertEquals(80.0, sli.observedMs());
        assertEquals(0, sli.breachingCoreQueries());
        assertEquals(LatencySli.MEETING, sli.verdict());
        assertTrue(sli.note().contains("과소평가")); // 정직: 추정치 한계 표기
    }

    @Test
    void 백분위_미지원_기종은_평균_레이턴시로_폴백하고_AVG_FALLBACK으로_표기한다() {
        // Oracle — UNSUPPORTED 안내 행. queryStats 평균 = 300000ms/2000calls = 150ms > 임계 → BREACHING
        List<LatencyPercentile> rows = List.of(LatencyPercentile.unsupported("Oracle 백분위 미지원"));
        List<QueryStat> stats = List.of(stat(1000, 100000), stat(1000, 200000));

        LatencySli sli = service.computeLatency(rows, stats);

        assertEquals(LatencySli.AVG_FALLBACK, sli.source());
        assertEquals(150.0, sli.observedMs());
        assertNull(sli.p95Ms()); // 폴백을 실측 백분위인 척하지 않는다
        assertEquals(1, sli.breachingCoreQueries());
        assertEquals(LatencySli.BREACHING, sli.verdict());
        assertTrue(sli.note().contains("폴백"));
    }

    @Test
    void 쿼리통계도_백분위도_없으면_레이턴시는_데이터_부족이다() {
        LatencySli sli = service.computeLatency(List.of(LatencyPercentile.unsupported("미지원")), List.of());

        assertEquals(LatencySli.INSUFFICIENT_DATA, sli.source());
        assertEquals(LatencySli.INSUFFICIENT_DATA, sli.verdict());
        assertNull(sli.observedMs());
    }

    // ---------- 가용성 SLI ----------

    @Test
    void 가용성은_윈도우_up비율로_판정한다() {
        // 1000 표본 중 998 up = 99.8% >= 99.5% → MEETING
        AvailabilitySli meeting = service.computeAvailability(1000, 998);
        assertEquals(0.998, meeting.upRatio(), 1e-9);
        assertEquals(AvailabilitySli.MEETING, meeting.verdict());

        // 1000 중 990 up = 99.0% < 99.5% → BREACHING
        AvailabilitySli breaching = service.computeAvailability(1000, 990);
        assertEquals(AvailabilitySli.BREACHING, breaching.verdict());
    }

    @Test
    void 가용성_표본이_최소치_미만이면_데이터_부족이다() {
        AvailabilitySli sli = service.computeAvailability(3, 3);
        assertEquals(AvailabilitySli.INSUFFICIENT_DATA, sli.verdict());
        assertEquals(1.0, sli.upRatio(), 1e-9); // 비율은 계산하되 판정만 보류
    }

    // ---------- 에러 버짓 · 번인 레이트 ----------

    @Test
    void 버짓_소진율과_번인레이트를_산식대로_계산한다() {
        // 허용 = (1-0.995)×30×24×60 = 216분. down 10샘플×1분 = 10분 관측 → 소진 10/216 ≈ 0.0463
        // 번인창: 최근 60표본 중 down 6 → 다운율 0.1 / 0.005 = 20.0 (지속가능 속도의 20배 → WARNING)
        ErrorBudget b = service.computeBudget(1000, 990, 60, 54);

        assertEquals(216.0, b.allowedDowntimeMinutes(), 0.01);
        assertEquals(10.0, b.observedDowntimeMinutes(), 0.01);
        assertEquals(0.0463, b.budgetConsumedRatio(), 0.0005);
        assertEquals(0.9537, b.budgetRemainingRatio(), 0.0005);
        assertEquals(20.0, b.burnRate(), 0.01);
        assertEquals(ErrorBudget.WARNING, b.verdict()); // 번인 레이트 > 1 → 임박 경고
    }

    @Test
    void 관측_다운타임이_허용을_넘으면_버짓_소진이다() {
        // down 300샘플×1분 = 300분 > 216분 허용 → 소진율 > 1 → EXHAUSTED
        ErrorBudget b = service.computeBudget(1000, 700, 60, 60);
        assertTrue(b.budgetConsumedRatio() > 1.0);
        assertEquals(ErrorBudget.EXHAUSTED, b.verdict());
    }

    @Test
    void 버짓이_넉넉하고_번인이_느리면_OK다() {
        // down 1샘플 = 1분 관측, 최근 60표본 전부 up → 번인 0 → OK
        ErrorBudget b = service.computeBudget(1000, 999, 60, 60);
        assertEquals(ErrorBudget.OK, b.verdict());
        assertEquals(0.0, b.burnRate(), 1e-9);
    }

    @Test
    void 버짓_표본이_최소치_미만이면_데이터_부족이다() {
        ErrorBudget b = service.computeBudget(3, 3, 3, 3);
        assertEquals(ErrorBudget.INSUFFICIENT_DATA, b.verdict());
        assertNull(b.budgetConsumedRatio());
        assertNull(b.burnRate()); // 번인창 표본이 있어도 회계기간 표본 부족이면 소진 판정 자체를 보류
    }

    // ---------- overall 판정 ----------

    @Test
    void overall은_위반을_최우선으로_본다() {
        LatencySli latOk = service.computeLatency(List.of(pct("a", 10, 20, LatencyPercentile.NATIVE)), List.of());
        AvailabilitySli availBreach = service.computeAvailability(1000, 900);
        ErrorBudget budgetOk = service.computeBudget(1000, 999, 60, 60);
        assertEquals(SloReport.BREACHING, SloService.overallVerdict(latOk, availBreach, budgetOk));
    }

    @Test
    void overall은_둘_다_데이터_부족이면_부족이다() {
        LatencySli latNone = service.computeLatency(List.of(LatencyPercentile.unsupported("x")), List.of());
        AvailabilitySli availNone = service.computeAvailability(0, 0);
        ErrorBudget budgetNone = service.computeBudget(0, 0, 0, 0);
        assertEquals(SloReport.INSUFFICIENT_DATA, SloService.overallVerdict(latNone, availNone, budgetNone));
    }

    // ---------- evaluate() 통합(모킹) — MySQL p95 SLI · Oracle 평균 폴백 ----------

    @Test
    void evaluate는_MySQL이면_p95_SLI를_Oracle이면_평균_폴백을_낸다() {
        DatabaseInstance mysql = new DatabaseInstance("mysql", DbmsType.MYSQL, "h", 3306, "d", "u", "p");
        DbmsOperator op = Mockito.mock(DbmsOperator.class);
        when(operatorFactory.create(any())).thenReturn(op);
        when(op.latencyPercentiles(anyInt())).thenReturn(List.of(pct("a", 130, 200, LatencyPercentile.NATIVE)));
        when(sampleRepository.countByInstanceIdAndSampledAtAfter(any(), any())).thenReturn(100L);
        when(sampleRepository.countByInstanceIdAndUpAndSampledAtAfter(any(), eq(true), any())).thenReturn(100L);

        SloReport report = service.evaluate(mysql, LocalDateTime.now());
        assertEquals(LatencyPercentile.NATIVE, report.latency().source());
        assertEquals(130.0, report.latency().observedMs());
        assertEquals(SloReport.BREACHING, report.verdict()); // p95 130 > 100

        // Oracle — 백분위 UNSUPPORTED, 평균 폴백. queryStats 평균 50ms < 임계 → 레이턴시 MEETING
        DatabaseInstance oracle = new DatabaseInstance("ora", DbmsType.ORACLE, "h", 1521, "d", "u", "p");
        when(op.latencyPercentiles(anyInt())).thenReturn(List.of(LatencyPercentile.unsupported("Oracle 미지원")));
        when(op.queryStats(anyInt())).thenReturn(List.of(stat(1000, 50000)));

        SloReport oraReport = service.evaluate(oracle, LocalDateTime.now());
        assertEquals(LatencySli.AVG_FALLBACK, oraReport.latency().source());
        assertEquals(50.0, oraReport.latency().observedMs());
        assertEquals(LatencySli.MEETING, oraReport.latency().verdict());
    }
}
