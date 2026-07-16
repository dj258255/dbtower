package io.dbtower.alert;

import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.alert.internal.job.AnomalyDetector;
import io.dbtower.insight.BaselineService;
import io.dbtower.insight.BaselineService.AnomalyScan;
import io.dbtower.insight.BaselineService.MetricAnomaly;
import io.dbtower.insight.BaselineService.QueryAnomaly;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 베이스라인 이상 감지 폴러 — 이상 스캔을 알림으로 옮기는 배선과 쿨다운 검증.
 * 판정 자체(z-score·학습 중)는 BaselineServiceTest가 고정하므로, 여기선 스캔 결과를 주입해
 * "이상이 있으면 웹훅, 같은 쿼리는 쿨다운 동안 침묵"만 본다(RegressionDetectorTest와 같은 결).
 */
class AnomalyDetectorTest {

    private final DatabaseInstanceRepository instanceRepository = Mockito.mock(DatabaseInstanceRepository.class);
    private final BaselineService baselineService = Mockito.mock(BaselineService.class);
    private final WebhookNotifier notifier = Mockito.mock(WebhookNotifier.class);

    private AnomalyDetector detector;

    @BeforeEach
    void setUp() {
        detector = new AnomalyDetector(instanceRepository, baselineService, notifier, 30);
        DatabaseInstance instance = new DatabaseInstance(
                "test-db", DbmsType.MYSQL, "127.0.0.1", 3306, "sample", "root", "pw");
        when(instanceRepository.findAll()).thenReturn(List.of(instance));
    }

    private void stubScan(QueryAnomaly... queries) {
        when(baselineService.detectAnomalies(any(), any()))
                .thenReturn(new AnomalyScan(1L, 1, 14, 8, 3.0, List.of(queries), 0));
    }

    private QueryAnomaly qpsSpike(String queryId) {
        return new QueryAnomaly(queryId, "SELECT * FROM t", 1, 14, 20, true,
                List.of(new MetricAnomaly("qps", 1.0, 0.1, 0.01, 60.0)));
    }

    private String notifiedMessage() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(notifier).sendEmbed(captor.capture(), any(), any());
        return captor.getValue();
    }

    @Test
    void 이상이_있으면_평소_대비_이탈로_알린다() {
        stubScan(qpsSpike("q1"));
        detector.detect();
        String msg = notifiedMessage();
        assertTrue(msg.contains("평소 대비 이탈"));
        assertTrue(msg.contains("qps"));
        assertTrue(msg.contains("z="));
    }

    @Test
    void 이상이_없으면_알리지_않는다() {
        when(baselineService.detectAnomalies(any(), any()))
                .thenReturn(new AnomalyScan(1L, 1, 14, 8, 3.0, List.of(), 3)); // 학습 중 3건뿐
        detector.detect();
        verify(notifier, never()).sendEmbed(anyString(), any(), any());
    }

    @Test
    void 같은_쿼리는_쿨다운_동안_다시_알리지_않는다() {
        stubScan(qpsSpike("q1"));
        detector.detect();
        detector.detect(); // 쿨다운 30분 안 재감지
        verify(notifier, times(1)).sendEmbed(anyString(), any(), any());
    }

    @Test
    void 감지_예외는_조용히_넘어간다() {
        when(baselineService.detectAnomalies(any(), any()))
                .thenThrow(new RuntimeException("boom"));
        assertDoesNotThrow(detector::detect);
        verify(notifier, never()).sendEmbed(anyString(), any(), any());
    }
}
