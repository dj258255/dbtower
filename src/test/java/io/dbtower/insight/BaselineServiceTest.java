package io.dbtower.insight;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * D1 베이스라인 계산·이상 판정·데이터 부족 처리 검증.
 *
 * 핵심 산식이 세 가지다: (1) 인접 배치 차분을 (요일×시간대) 버킷에 모아 평균·표준편차를 학습,
 * (2) 현재 구간 값의 z-score가 임계를 넘으면 이상, (3) 관측이 부족하면 판정 보류("학습 중").
 * 시간·요일에 의존하므로, 이력을 now 기준 상대 시각(now-7일, now-6일)으로 생성해
 * 실행일과 무관하게 "같은 요일·시간대"가 성립하도록 고정한다.
 */
class BaselineServiceTest {

    private final QuerySnapshotRepository repository = Mockito.mock(QuerySnapshotRepository.class);
    // 장기 베이스라인(D8)은 기본 "빈 테이블" — 기존 판정 테스트는 D8 이전과 동일하게 동작해야 한다(회귀 0).
    private final io.dbtower.insight.internal.BaselineLongtermDao longtermDao =
            Mockito.mock(io.dbtower.insight.internal.BaselineLongtermDao.class);
    // historyDays=14, recentMinutes=5, zThreshold=3.0, minObservations=8, longterm 병합 on
    private final BaselineService service = new BaselineService(repository, longtermDao, 14, 5, 3.0, 8, true);

    {
        when(longtermDao.findBucket(any(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(java.util.Map.of());
    }

    // 14시대의 고정 시각 — now.getHour()==14라 findForHourBucket(hour=14)로 조회된다.
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 6, 14, 30);

    private QuerySnapshot snap(LocalDateTime at, String queryId, long calls, double timeMs, long rows) {
        return new QuerySnapshot(1L, at, queryId, "SELECT " + queryId, calls, timeMs, rows);
    }

    /**
     * now-7일(같은 요일·시간대)에 배치 9개 → 인접 차분 8개 관측.
     * 분당 A가 60회(QPS 1.0), 호출당 10ms, 호출당 2행으로 완전히 안정적인 평소치.
     * (QPS·레이턴시 평균을 노이즈 게이트 바닥보다 넉넉히 위에 둬서 임계 경계 애매함을 피한다.)
     */
    private List<QuerySnapshot> stableHistory(LocalDateTime dayAnchor) {
        LocalDateTime start = dayAnchor.withMinute(0).withSecond(0).withNano(0); // 14:00
        List<QuerySnapshot> rows = new ArrayList<>();
        for (int i = 0; i < 9; i++) { // 14:00 ~ 14:08, 배치 9개
            rows.add(snap(start.plusMinutes(i), "A", 1000 + 60L * i, 10000 + 600.0 * i, 2000 + 120L * i));
        }
        return rows;
    }

    private void stubBaseline(List<QuerySnapshot> history) {
        // 베이스라인 조회: 시간대(14) 버킷의 과거 이력. 시각 인자는 느슨하게 any()로 둔다.
        when(repository.findForHourBucket(eq(1L), any(), any(), eq(14))).thenReturn(history);
    }

    private void stubCurrent(List<QuerySnapshot> current) {
        when(repository.findByInstanceIdAndCapturedAtBetweenOrderByCapturedAt(eq(1L), any(), any()))
                .thenReturn(current);
    }

    @Test
    void 안정적_이력_대비_QPS_급증을_이상으로_잡는다() {
        stubBaseline(stableHistory(NOW.minusDays(7)));
        // 현재 5분 창(300초): A가 1500회 -> QPS 5.0(평소 1.0의 5배). 레이턴시·행수는 평소 유지.
        stubCurrent(List.of(
                snap(NOW.minusMinutes(5), "A", 2000, 200_000, 4000),
                snap(NOW, "A", 3500, 215_000, 7000)));

        var scan = service.detectAnomalies(1L, NOW);

        assertEquals(0, scan.learningCount());
        assertEquals(1, scan.anomalies().size());
        var q = scan.anomalies().get(0);
        assertEquals("A", q.queryId());
        assertTrue(q.baselineAvailable());
        assertEquals(8, q.observations());
        assertEquals(1, q.anomalies().size(), "QPS만 이탈해야 한다(레이턴시·행수는 평소 유지)");
        var m = q.anomalies().get(0);
        assertEquals("qps", m.metric());
        assertEquals(5.0, m.current(), 0.01);
        assertEquals(1.0, m.baselineMean(), 0.01);
        assertTrue(m.zScore() >= 3.0, "z-score가 임계를 크게 넘어야 한다: " + m.zScore());
    }

    @Test
    void 안정적_이력_대비_레이턴시_회귀를_잡는다() {
        stubBaseline(stableHistory(NOW.minusDays(7)));
        // QPS는 평소(1.0) 유지, 호출당 10ms -> 100ms로 10배: 300회, 시간 30000ms, 행 600.
        stubCurrent(List.of(
                snap(NOW.minusMinutes(5), "A", 2000, 200_000, 4000),
                snap(NOW, "A", 2300, 230_000, 4600)));

        var scan = service.detectAnomalies(1L, NOW);

        assertEquals(1, scan.anomalies().size());
        var m = scan.anomalies().get(0).anomalies().get(0);
        assertEquals("latencyMs", m.metric());
        assertEquals(100.0, m.current(), 0.01);
        assertEquals(10.0, m.baselineMean(), 0.01);
    }

    @Test
    void 평소_수준의_현재값은_이상이_아니다() {
        stubBaseline(stableHistory(NOW.minusDays(7)));
        // 300초에 300회 -> QPS 1.0, 10ms, 2행 — 전부 평소치와 동일.
        stubCurrent(List.of(
                snap(NOW.minusMinutes(5), "A", 2000, 200_000, 4000),
                snap(NOW, "A", 2300, 203_000, 4600)));

        var scan = service.detectAnomalies(1L, NOW);

        assertTrue(scan.anomalies().isEmpty());
        assertEquals(0, scan.learningCount());
    }

    @Test
    void 관측이_최소치_미만이면_학습중으로_판정을_보류한다() {
        // 배치 3개 -> 관측 2개 (minObservations=8 미만) => 이상으로 단정하지 않는다(신규 오탐 방지).
        LocalDateTime start = NOW.minusDays(7).withMinute(0).withSecond(0).withNano(0);
        stubBaseline(List.of(
                snap(start, "A", 1000, 10000, 2000),
                snap(start.plusMinutes(1), "A", 1060, 10600, 2120),
                snap(start.plusMinutes(2), "A", 1120, 11200, 2240)));
        stubCurrent(List.of(
                snap(NOW.minusMinutes(5), "A", 2000, 200_000, 4000),
                snap(NOW, "A", 3500, 215_000, 7000))); // QPS 급증이지만

        var scan = service.detectAnomalies(1L, NOW);

        assertTrue(scan.anomalies().isEmpty(), "이력 부족이면 급증이어도 판정 보류");
        assertEquals(1, scan.learningCount());
    }

    @Test
    void 다른_요일의_같은_시간대_관측은_베이스라인에서_제외된다() {
        // findForHourBucket은 요일 무관 14시대를 다 준다 -> 서비스가 요일(now의 요일)로 걸러야 한다.
        // now-6일은 now와 다른 요일이므로 관측 0 -> 학습 중이 되어야 한다.
        stubBaseline(stableHistory(NOW.minusDays(6)));
        stubCurrent(List.of(
                snap(NOW.minusMinutes(5), "A", 2000, 200_000, 4000),
                snap(NOW, "A", 3500, 215_000, 7000)));

        var scan = service.detectAnomalies(1L, NOW);

        assertTrue(scan.anomalies().isEmpty());
        assertEquals(1, scan.learningCount(), "다른 요일 관측만 있으면 이 버킷은 이력 0 => 학습 중");
    }

    @Test
    void 현재_배치가_2개_미만이면_예외없이_빈_결과다() {
        stubBaseline(stableHistory(NOW.minusDays(7)));
        stubCurrent(List.of(snap(NOW, "A", 2000, 100_000, 4000))); // 배치 1개

        var scan = service.detectAnomalies(1L, NOW);

        assertTrue(scan.anomalies().isEmpty());
        assertEquals(0, scan.learningCount());
    }

    // ---------- D8: 장기 베이스라인 병합 (lakehouse reverse ETL) ----------
    // NOW(2026-07-06 14시)는 월요일 — java getValue()=1, lakehouse 규약(일=0..토=6)으로도 1.

    private void stubLongterm(String queryId, long observations, double meanDeltaCallsPerHour,
                              double stddevDeltaCallsPerHour) {
        when(longtermDao.findBucket(eq(1L), eq(1), eq(14))).thenReturn(java.util.Map.of(
                queryId, new io.dbtower.insight.internal.BaselineLongtermDao.LongtermStat(
                        observations, meanDeltaCallsPerHour, stddevDeltaCallsPerHour)));
    }

    @Test
    void 장기_테이블이_비면_병합_전과_완전히_동일하다_회귀0() {
        // 같은 입력을 두 서비스(장기 스위치 on/빈 테이블 vs off)에 넣어 판정이 일치해야 한다.
        stubBaseline(stableHistory(NOW.minusDays(7)));
        stubCurrent(List.of(
                snap(NOW.minusMinutes(5), "A", 2000, 200_000, 4000),
                snap(NOW, "A", 3500, 215_000, 7000)));
        var off = new BaselineService(repository, longtermDao, 14, 5, 3.0, 8, false);

        var withEmpty = service.detectAnomalies(1L, NOW);
        var disabled = off.detectAnomalies(1L, NOW);

        assertEquals(disabled.anomalies().size(), withEmpty.anomalies().size());
        assertEquals(disabled.learningCount(), withEmpty.learningCount());
        assertEquals(disabled.anomalies().get(0).anomalies().get(0).zScore(),
                withEmpty.anomalies().get(0).anomalies().get(0).zScore(), 0.001);
    }

    @Test
    void 장기_관측이_학습중을_판정가능으로_바꾼다() {
        // 단기 이력 전무(학습 중이던 케이스) + 장기가 이 버킷을 100관측으로 안다(평소 QPS 1.0
        // = 시간당 3600콜, 표준편차도 넉넉) → 판정 가능해지고, 현재 QPS 5.0은 이상으로 잡힌다.
        stubBaseline(List.of());
        stubLongterm("A", 100, 3600.0, 360.0);
        stubCurrent(List.of(
                snap(NOW.minusMinutes(5), "A", 2000, 200_000, 4000),
                snap(NOW, "A", 3500, 215_000, 7000)));

        var scan = service.detectAnomalies(1L, NOW);

        assertEquals(0, scan.learningCount(), "장기 100관측이 게이트(8)를 넘긴다");
        assertEquals(1, scan.anomalies().size());
        var q = scan.anomalies().get(0);
        assertEquals(100, q.observations());
        assertEquals(1, q.anomalies().size(), "장기가 나르는 축은 QPS뿐 — 레이턴시·행수는 단기 부족으로 보류");
        assertEquals("qps", q.anomalies().get(0).metric());
    }

    @Test
    void 월요일_피크를_아는_장기_평균이_오탐을_없앤다() {
        // 단기 14일 창: 평소 QPS 1.0 안정(8관측) — 현재 5.0은 z가 임계를 훌쩍 넘는 "이상"이다.
        // 그런데 장기(수개월)는 이 버킷(월요일 14시)의 평소가 원래 QPS 5.0(시간당 18000콜)임을 안다.
        // 병합 후 평균이 5.0 쪽으로 끌려가 z가 임계 밑으로 내려와야 한다 — 주간 계절성 오탐 제거.
        stubBaseline(stableHistory(NOW.minusDays(7)));
        stubLongterm("A", 100, 18_000.0, 1_800.0);
        stubCurrent(List.of(
                snap(NOW.minusMinutes(5), "A", 2000, 200_000, 4000),
                snap(NOW, "A", 3500, 215_000, 7000)));

        var scan = service.detectAnomalies(1L, NOW);

        assertTrue(scan.anomalies().isEmpty(),
                "장기 평균(5.0)과 같은 현재값(5.0)은 이상이 아니다 — 계절성 오탐 제거");
        assertEquals(0, scan.learningCount());
    }
}
