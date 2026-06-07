package io.dbtower.alert;

import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.QueryDiff;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 회귀 감지 4규칙과 쿨다운 검증.
 * 임계값(200%/500%, 최소 QPS/레이턴시)은 노이즈 억제 장치라서,
 * "임계 바로 아래는 조용하고 넘으면 울린다"를 고정해 둔다.
 */
class RegressionDetectorTest {

    private final DatabaseInstanceRepository instanceRepository = Mockito.mock(DatabaseInstanceRepository.class);
    private final ComparisonService comparisonService = Mockito.mock(ComparisonService.class);
    private final WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);
    private final AiAnalyzer aiAnalyzer = Mockito.mock(AiAnalyzer.class);

    private RegressionDetector detector;

    @BeforeEach
    void setUp() {
        detector = new RegressionDetector(instanceRepository, comparisonService, notifier, aiAnalyzer,
                5, 15, 30);
        DatabaseInstance instance = new DatabaseInstance(
                "test-db", DbmsType.MYSQL, "127.0.0.1", 3306, "sample", "root", "pw");
        when(instanceRepository.findAll()).thenReturn(List.of(instance));
        when(aiAnalyzer.analyze(anyString())).thenReturn(Optional.empty());
    }

    private void stubDiffs(QueryDiff... diffs) {
        var summary = new ComparisonService.WindowSummary(0, 0, 0, 0, diffs.length);
        when(comparisonService.compare(any(), any(), any(), any(), any()))
                .thenReturn(new ComparisonService.CompareResult(
                        summary, summary, null, null, null, 0, List.of(diffs)));
    }

    private String notifiedMessage() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void 신규_쿼리_유입을_알린다() {
        stubDiffs(new QueryDiff("q1", "SELECT * FROM t", 0, 2.0, null, 0, 3.0, null, 0, 8_000, null, true));
        detector.detect();
        assertTrue(notifiedMessage().contains("신규 쿼리 유입"));
    }

    @Test
    void 호출량_급증은_200퍼센트_이상일_때만_알린다() {
        // +199%는 임계 미달 — 조용해야 한다
        stubDiffs(new QueryDiff("q1", "Q", 1.0, 2.99, 199.0, 1, 1, 0.0, 0, 0, null, false));
        detector.detect();
        verify(notifier, never()).send(anyString());

        // +200%는 알림
        stubDiffs(new QueryDiff("q2", "Q", 1.0, 3.0, 200.0, 1, 1, 0.0, 0, 0, null, false));
        detector.detect();
        assertTrue(notifiedMessage().contains("호출량 급증"));
    }

    @Test
    void 레이턴시_회귀와_행수_폭증도_각각_잡는다() {
        stubDiffs(
                new QueryDiff("q1", "SLOW", 1, 1, 0.0, 2.0, 8.0, 300.0, 0, 0, null, false),
                new QueryDiff("q2", "SCAN", 1, 1, 0.0, 1, 1, 0.0, 10, 8_000, 79_900.0, false));
        detector.detect();
        String message = notifiedMessage();
        assertTrue(message.contains("레이턴시 회귀"));
        assertTrue(message.contains("읽는 행수 폭증"));
    }

    @Test
    void 같은_쿼리는_쿨다운_동안_다시_알리지_않는다() {
        stubDiffs(new QueryDiff("q1", "Q", 0, 2.0, null, 0, 3.0, null, 0, 0, null, true));
        detector.detect();
        detector.detect(); // 쿨다운 30분 안의 재감지
        verify(notifier, times(1)).send(anyString());
    }

    @Test
    void 스냅샷_부족_예외는_조용히_넘어간다() {
        when(comparisonService.compare(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("배치가 2개 이상 필요합니다"));
        assertDoesNotThrow(detector::detect);
        verify(notifier, never()).send(anyString());
    }
}
