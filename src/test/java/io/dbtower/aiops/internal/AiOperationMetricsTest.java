package io.dbtower.aiops.internal;

import io.dbtower.aiops.AiOperationStatus;
import io.dbtower.aiops.internal.persistence.AiOperationJobRepository;
import io.dbtower.aiops.internal.persistence.AiOperationOutboxRepository;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 게이지 계약 — 어떤 값이 어떤 이름·태그로 나오는지 못박는다.
 *
 * <p>스프링 컨텍스트 없이 도는 이유: 이 클래스의 위험은 배선이 아니라 (1) 쿼리 결과를 게이지에 옮기는 계산과
 * (2) 태그 카디널리티다. 후자는 메트릭이 늘 때마다 조용히 어긋나는 종류라 여기서 강제한다.</p>
 */
class AiOperationMetricsTest {

    // 시각을 고정한다 — "가장 오래 멈춘 작업"의 경과 초를 범위 단언이 아니라 정확한 값으로 못 박기 위해서다
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private SimpleMeterRegistry meters;
    private AiOperationJobRepository jobs;
    private AiOperationOutboxRepository outbox;
    private AiOperationMetrics metrics;

    @BeforeEach
    void setUp() {
        // 레지스트리를 테스트마다 새로 만든다 — 같은 이름·태그의 게이지는 먼저 등록된 쪽이 계속 살아 있어
        // 재사용하면 두 번째 인스턴스의 갱신이 게이지에 닿지 않는다(통과하는 것처럼 보이는 거짓 테스트가 된다)
        meters = new SimpleMeterRegistry();
        jobs = mock(AiOperationJobRepository.class);
        outbox = mock(AiOperationOutboxRepository.class);
        metrics = new AiOperationMetrics(meters, jobs, outbox, CLOCK);
    }

    @Test
    void 미발행_이벤트_수를_게이지로_노출한다() {
        when(outbox.countUnpublished()).thenReturn(7L);

        metrics.refresh();

        assertThat(meters.get("dbtower.aiops.outbox.unpublished").gauge().value()).isEqualTo(7);
    }

    @Test
    void 상태별_진행_중_수를_status_태그로_노출하고_쿼리에_없는_상태는_0이다() {
        when(jobs.countByStatuses(any())).thenReturn(counts(
                new Object[]{AiOperationStatus.RECEIVED, 2L},
                new Object[]{AiOperationStatus.ANALYZING, 1L}));

        metrics.refresh();

        assertThat(active("received")).isEqualTo(2);
        assertThat(active("analyzing")).isEqualTo(1);
        // 쿼리에 없는 진행 중 상태도 0으로 남긴다 — 시계열이 없으면 그래프에서 사라져 "작업 없음"과
        // "수집이 멈춤"이 구분되지 않는다
        assertThat(active("collecting")).isZero();
        assertThat(active("retrieving")).isZero();
        assertThat(active("verifying")).isZero();
    }

    @Test
    void 종료_상태는_게이지를_만들지_않는다() {
        // 누적 수는 이미 dbtower.aiops.jobs.finished 계수가 센다 — 게이지로 또 만들면 두 이름이 같은 뜻을 갖는다
        assertThat(meters.find("dbtower.aiops.jobs.active").tag("status", "completed").gauge()).isNull();
        assertThat(meters.find("dbtower.aiops.jobs.active").tag("status", "failed").gauge()).isNull();
        assertThat(meters.find("dbtower.aiops.jobs.active").tag("status", "cancelled").gauge()).isNull();
    }

    @Test
    void 가장_오래_진전이_없는_작업의_경과_초를_노출하고_작업이_없으면_0이다() {
        // 기준은 updatedAt이다 — 사흘 전 접수돼 방금 재시도된 작업이 게이지를 수만 초로 띄우면 거짓 신호다
        when(jobs.oldestProgressAt(any())).thenReturn(NOW.minusSeconds(90));

        metrics.refresh();

        assertThat(stalledSeconds()).isEqualTo(90);

        // 진행 중 작업이 없으면 min()은 null이다 — 0을 넣어야 그래프가 끊기지 않는다
        when(jobs.oldestProgressAt(any())).thenReturn(null);
        metrics.refresh();

        assertThat(stalledSeconds()).isZero();
    }

    @Test
    void 갱신이_실패해도_예외를_올리지_않고_이전_값을_유지한다() {
        when(jobs.countByStatuses(any())).thenReturn(counts(new Object[]{AiOperationStatus.COLLECTING, 3L}));
        when(outbox.countUnpublished()).thenReturn(4L);
        when(jobs.oldestProgressAt(any())).thenReturn(NOW.minusSeconds(30));
        metrics.refresh();

        when(jobs.countByStatuses(any())).thenThrow(new IllegalStateException("DB 연결 끊김"));
        when(jobs.oldestProgressAt(any())).thenThrow(new IllegalStateException("DB 연결 끊김"));
        when(outbox.countUnpublished()).thenThrow(new IllegalStateException("DB 연결 끊김"));

        // 메트릭 갱신이 앱을 흔들면 안 된다 — 실패는 로그 한 줄로 삼킨다
        assertThatCode(() -> metrics.refresh()).doesNotThrowAnyException();

        // 0으로 떨어뜨리지 않는다. 0이면 "작업 없음"으로 보여 진짜 정지를 놓친다
        assertThat(active("collecting")).isEqualTo(3);
        assertThat(meters.get("dbtower.aiops.outbox.unpublished").gauge().value()).isEqualTo(4);
        assertThat(stalledSeconds()).isEqualTo(30);
    }

    @Test
    void 태그에는_status_축만_쓴다() {
        when(jobs.countByStatuses(any())).thenReturn(counts(new Object[]{AiOperationStatus.COLLECTING, 3L}));
        when(jobs.oldestProgressAt(any())).thenReturn(NOW.minusSeconds(30));
        when(outbox.countUnpublished()).thenReturn(1L);

        metrics.refresh();

        // 작업 id·인스턴스 이름·요청자를 태그로 붙이면 작업 수만큼 시계열이 늘어난다 — 등록된 미터 전체를 훑어 막는다
        assertThat(meters.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey))
                .containsOnly("status");
        assertThat(meters.get("dbtower.aiops.jobs.active").tag("status", "collecting").gauge().getId().getTags())
                .containsExactly(Tag.of("status", "collecting"));
    }

    private double active(String status) {
        return meters.get("dbtower.aiops.jobs.active").tag("status", status).gauge().value();
    }

    private double stalledSeconds() {
        return meters.get("dbtower.aiops.jobs.stalled.seconds").gauge().value();
    }

    private static List<Object[]> counts(Object[]... rows) {
        return List.of(rows);
    }
}
