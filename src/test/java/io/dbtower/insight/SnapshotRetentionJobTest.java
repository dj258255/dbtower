package io.dbtower.insight;

import ch.qos.logback.classic.Level;
import io.dbtower.insight.internal.job.SnapshotRetentionJob;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보존 정책의 세 가지 계약을 고정한다:
 * cutoff는 정확히 보존일수 전이어야 하고(하루만 어긋나도 데이터가 사라지거나 남는다),
 * 0 이하는 "보존 무제한" 오프 스위치이며, 삭제 결과는 건수에 따라 info/debug로 남는다.
 */
class SnapshotRetentionJobTest {

    private final QuerySnapshotRepository repository = Mockito.mock(QuerySnapshotRepository.class);

    private final Logger jobLogger = (Logger) LoggerFactory.getLogger(SnapshotRetentionJob.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        // debug 경로까지 관찰해야 하므로 이 로거만 잠시 DEBUG로 내린다
        originalLevel = jobLogger.getLevel();
        jobLogger.setLevel(Level.DEBUG);
        logs.start();
        jobLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        jobLogger.detachAppender(logs);
        jobLogger.setLevel(originalLevel);
    }

    @Test
    void 기본_보존_7일이면_정확히_7일_전이_cutoff다() {
        when(repository.deleteByCapturedAtBefore(any())).thenReturn(3);
        SnapshotRetentionJob job = new SnapshotRetentionJob(repository, 7);

        LocalDateTime before = LocalDateTime.now();
        job.sweep();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteByCapturedAtBefore(captor.capture());
        LocalDateTime cutoff = captor.getValue();
        // now는 고정할 수 없으므로 호출 전후 시각으로 감싸서 검증한다
        assertFalse(cutoff.isBefore(before.minusDays(7)), "cutoff가 7일 전보다 과거면 덜 지운다");
        assertFalse(cutoff.isAfter(after.minusDays(7)), "cutoff가 7일 전보다 미래면 보존 대상까지 지운다");
    }

    @Test
    void 보존일수_0이면_삭제하지_않는다() {
        new SnapshotRetentionJob(repository, 0).sweep();
        verify(repository, never()).deleteByCapturedAtBefore(any());
    }

    @Test
    void 보존일수_음수도_보존_무제한으로_취급한다() {
        new SnapshotRetentionJob(repository, -1).sweep();
        verify(repository, never()).deleteByCapturedAtBefore(any());
    }

    @Test
    void 삭제가_있으면_건수를_info로_남긴다() {
        when(repository.deleteByCapturedAtBefore(any())).thenReturn(42);
        new SnapshotRetentionJob(repository, 7).sweep();

        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("deleted=42"));
    }

    @Test
    void 삭제할_것이_없으면_debug로만_남긴다() {
        when(repository.deleteByCapturedAtBefore(any())).thenReturn(0);
        new SnapshotRetentionJob(repository, 7).sweep();

        assertEquals(1, logs.list.size());
        assertEquals(Level.DEBUG, logs.list.get(0).getLevel());
    }
}
