package io.dbtower.insight;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 시점 비교의 핵심 산식 검증 — 누적 카운터의 구간 차분, QPS 정규화, 신규 쿼리 감지.
 * 이 산식이 틀리면 회귀 감지(RegressionDetector)까지 연쇄로 틀리므로 가장 먼저 고정한다.
 */
class ComparisonServiceTest {

    private final QuerySnapshotRepository repository = Mockito.mock(QuerySnapshotRepository.class);
    private final ComparisonService service = new ComparisonService(repository);

    private static final LocalDateTime BASE_FROM = LocalDateTime.of(2026, 7, 4, 10, 0);
    private static final LocalDateTime BASE_TO = LocalDateTime.of(2026, 7, 4, 10, 10);
    private static final LocalDateTime TARGET_FROM = LocalDateTime.of(2026, 7, 4, 14, 0);
    private static final LocalDateTime TARGET_TO = LocalDateTime.of(2026, 7, 4, 14, 10);

    private QuerySnapshot snap(LocalDateTime at, String queryId, long calls, double timeMs, long rows) {
        return new QuerySnapshot(1L, at, queryId, "SELECT " + queryId, calls, timeMs, rows);
    }

    private void stubWindows(List<QuerySnapshot> base, List<QuerySnapshot> target) {
        when(repository.findByInstanceIdAndCapturedAtBetweenOrderByCapturedAt(1L, BASE_FROM, BASE_TO))
                .thenReturn(base);
        when(repository.findByInstanceIdAndCapturedAtBetweenOrderByCapturedAt(1L, TARGET_FROM, TARGET_TO))
                .thenReturn(target);
    }

    @Test
    void 누적_카운터의_양끝_배치_차분이_구간_발생량이_된다() {
        // base 구간(600초): A가 60회 -> QPS 0.1, 호출당 5ms
        stubWindows(
                List.of(snap(BASE_FROM, "A", 100, 1_000, 1_000),
                        snap(BASE_TO, "A", 160, 1_300, 1_600)),
                // target 구간(600초): A가 120회 -> QPS 0.2, 호출당 10ms
                List.of(snap(TARGET_FROM, "A", 500, 5_000, 5_000),
                        snap(TARGET_TO, "A", 620, 6_200, 6_200)));

        var result = service.compare(1L, BASE_FROM, BASE_TO, TARGET_FROM, TARGET_TO);

        assertEquals(60, result.base().totalCalls());
        assertEquals(120, result.target().totalCalls());
        QueryDiff diff = result.queries().get(0);
        assertEquals(0.1, diff.baseQps(), 0.001);
        assertEquals(0.2, diff.targetQps(), 0.001);
        assertEquals(100.0, diff.qpsChangePct(), 0.01);   // 0.1 -> 0.2 = +100%
        assertEquals(5.0, diff.baseAvgMs(), 0.001);
        assertEquals(10.0, diff.targetAvgMs(), 0.001);
        assertFalse(diff.newQuery());
    }

    @Test
    void base_구간에_없던_쿼리는_신규로_표시되고_증감률은_null이다() {
        stubWindows(
                List.of(snap(BASE_FROM, "A", 100, 1_000, 0),
                        snap(BASE_TO, "A", 160, 1_300, 0)),
                List.of(snap(TARGET_FROM, "A", 200, 2_000, 0),
                        snap(TARGET_TO, "A", 260, 2_300, 0),
                        snap(TARGET_TO, "NEW_Q", 50, 500, 400_000)));

        var result = service.compare(1L, BASE_FROM, BASE_TO, TARGET_FROM, TARGET_TO);

        assertEquals(1, result.newQueryCount());
        QueryDiff newQ = result.queries().stream()
                .filter(QueryDiff::newQuery).findFirst().orElseThrow();
        assertEquals("NEW_Q", newQ.queryId());
        // base가 0이면 증감률은 계산 불가 — 0으로 나누는 대신 null (허위 수치 방지)
        assertNull(newQ.qpsChangePct());
        // 구간 중간에 처음 나타난 쿼리는 카운터 전체를 구간 발생량으로 본다 (50회/600초, 소수 2자리 반올림)
        assertEquals(0.08, newQ.targetQps(), 0.001);
    }

    @Test
    void 카운터_리셋으로_감소한_값은_0으로_클램프되어_비교에서_빠진다() {
        // DB 재기동으로 누적 카운터가 줄어든 상황 — 음수 발생량이라는 허위 데이터를 만들면 안 된다
        stubWindows(
                List.of(snap(BASE_FROM, "A", 100, 1_000, 0),
                        snap(BASE_TO, "A", 160, 1_300, 0)),
                List.of(snap(TARGET_FROM, "A", 9_999, 99_999, 0),
                        snap(TARGET_TO, "A", 10, 100, 0)));

        var result = service.compare(1L, BASE_FROM, BASE_TO, TARGET_FROM, TARGET_TO);

        // deltaCalls가 0으로 클램프 -> 실행되지 않은 쿼리로 취급되어 target에서 제외
        assertEquals(0, result.target().totalCalls());
        assertTrue(result.queries().isEmpty());
    }

    @Test
    void 구간_안_배치가_2개_미만이면_거부한다() {
        stubWindows(
                List.of(snap(BASE_FROM, "A", 100, 1_000, 0)), // 배치 1개뿐
                List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.compare(1L, BASE_FROM, BASE_TO, TARGET_FROM, TARGET_TO));
        assertTrue(e.getMessage().contains("2개 이상"));
    }

    @Test
    void 문제_구간에서_시간을_많이_쓴_쿼리가_앞에_온다() {
        stubWindows(
                List.of(snap(BASE_FROM, "light", 10, 10, 0),
                        snap(BASE_TO, "light", 20, 20, 0)),
                List.of(snap(TARGET_FROM, "light", 100, 100, 0),
                        snap(TARGET_FROM, "heavy", 100, 100, 0),
                        snap(TARGET_TO, "light", 110, 110, 0),
                        snap(TARGET_TO, "heavy", 200, 10_100, 0)));

        var result = service.compare(1L, BASE_FROM, BASE_TO, TARGET_FROM, TARGET_TO);

        assertEquals("heavy", result.queries().get(0).queryId());
    }
}
