package io.dbtower.aiops.internal;

import io.dbtower.aiops.AiOperationStatus;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.aiops.internal.persistence.AiOperationJobRepository;
import io.dbtower.aiops.internal.persistence.AiOperationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 운영 작업 파이프라인의 현재 상태를 노출한다 — 큐가 밀리는지, 어디서 멈췄는지, 얼마나 걸리는지.
 *
 * <p>기존 계수(`dbtower.aiops.jobs.finished`)는 끝난 뒤의 누적 수라 지금 무슨 일이 벌어지는지 말해주지 않는다.
 * 그래서 게이지를 붙이되 두 가지를 지킨다.</p>
 *
 * <p>(1) 스크레이프마다 DB를 때리지 않는다. Prometheus는 15초마다 긁으므로 게이지가 매번 count(*)를 돌리면
 * <b>스크레이프가 곧 부하</b>가 된다(대상 DB를 조회하는 폴러에도 같은 규칙을 적용해 왔다). 게이지는
 * {@link #refresh()}가 캐시한 숫자만 읽고, DB는 주기 갱신에서 한 번만 본다.</p>
 *
 * <p>(2) 태그는 `status` 하나뿐이다. 작업 id·인스턴스 이름·요청자를 태그로 쓰면 시계열이 요청 수만큼 늘어난다.
 * 진행 중이 아닌 상태는 게이지를 만들지 않는다 — COMPLETED·FAILED 누적 수는 이미 계수가 센다.</p>
 *
 * <p>갱신에 ShedLock을 걸지 않는다. HA에서 폴러는 "한 노드만" 도는 것이 맞지만 게이지는 다르다 —
 * 각 노드가 자기 관측값을 노출해야 어느 노드에 작업이 몰렸는지가 보인다. 값은 노드별로 남고,
 * 대시보드에서 노드를 합쳐 보는 것(`sum by (status)`)은 대시보드의 선택이다.</p>
 */
@Component
public class AiOperationMetrics {

    private static final Logger log = LoggerFactory.getLogger(AiOperationMetrics.class);

    /** 진행 중으로 보는 상태 — 리퍼가 리스 만료로 정리하는 구간에 아직 아무도 가져가지 않은 RECEIVED를 더한 것 */
    static final EnumSet<AiOperationStatus> ACTIVE = EnumSet.of(AiOperationStatus.RECEIVED,
            AiOperationStatus.AUTHORIZED, AiOperationStatus.COLLECTING, AiOperationStatus.RETRIEVING,
            AiOperationStatus.ANALYZING, AiOperationStatus.VERIFYING);

    private final MeterRegistry meterRegistry;
    private final AiOperationJobRepository jobs;
    private final AiOperationOutboxRepository outbox;
    private final Clock clock;
    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong stalledSeconds = new AtomicLong();
    private final Map<AiOperationStatus, AtomicLong> active = new EnumMap<>(AiOperationStatus.class);

    // 시계 주입 생성자는 테스트가 시각을 고정하는 시임이다 — AiOperationService와 같은 방식(공개는 시스템 시계)
    @Autowired
    public AiOperationMetrics(MeterRegistry meterRegistry, AiOperationJobRepository jobs,
                              AiOperationOutboxRepository outbox) {
        this(meterRegistry, jobs, outbox, Clock.systemDefaultZone());
    }

    AiOperationMetrics(MeterRegistry meterRegistry, AiOperationJobRepository jobs,
                       AiOperationOutboxRepository outbox, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.jobs = jobs;
        this.outbox = outbox;
        this.clock = clock;
        Gauge.builder("dbtower.aiops.outbox.unpublished", unpublished, AtomicLong::get)
                .description("큐로 아직 나가지 못한 접수 이벤트 수 — 릴레이가 멈추면 자란다")
                .register(meterRegistry);
        Gauge.builder("dbtower.aiops.jobs.stalled.seconds", stalledSeconds, AtomicLong::get)
                .description("진행 중 작업이 마지막으로 진전한 뒤 지난 최대 초")
                .register(meterRegistry);
        for (AiOperationStatus status : ACTIVE) {
            AtomicLong value = new AtomicLong();
            active.put(status, value);
            Gauge.builder("dbtower.aiops.jobs.active", value, AtomicLong::get)
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .description("진행 중인 AI 운영 작업 수")
                    .register(meterRegistry);
        }
    }

    /**
     * 끝난 작업 한 건을 남긴다 — 완료·실패·취소 전부. 기록이 워크플로에만 있으면 리퍼가 정리한
     * 리스 만료와 사람이 누른 취소가 지표에서 사라진다(멈춘 작업이야말로 보려던 것이다).
     */
    public void recordFinished(AiOperationType type, AiOperationStatus status, OffsetDateTime requestedAt) {
        String typeTag = type.name().toLowerCase(Locale.ROOT);
        String statusTag = status.name().toLowerCase(Locale.ROOT);
        meterRegistry.counter("dbtower.aiops.jobs.finished", "type", typeTag, "status", statusTag).increment();
        // 접수에서 끝까지 — 큐 대기·사실 수집·모델 호출이 전부 들어간, 요청자가 체감하는 시간.
        // 재시도된 작업은 실패 시점과 재시도 완료 시점에 각각 남는다(status 태그가 다르다)
        meterRegistry.timer("dbtower.aiops.jobs.duration", "type", typeTag, "status", statusTag)
                .record(Duration.between(requestedAt, OffsetDateTime.now(clock)));
    }

    /**
     * 게이지 값을 DB에서 한 번 읽어 캐시에 담는다. 테스트가 직접 부를 수 있게 public으로 둔다(스케줄 진입점이 곧 본체다).
     *
     * <p>전부 읽어 계산한 뒤 한 번에 반영한다 — 중간에 실패하면 <b>아무 값도 바꾸지 않는다</b>. 절반만 새 값이 되면
     * 같은 스크레이프 안에서 서로 다른 시점의 수가 섞여 보인다. 실패는 삼키고 이전 값을 유지하되, 조용히 넘어가면
     * "0으로 보이는 정지"와 "진짜 0"을 구분할 수 없으므로 로그는 남긴다.</p>
     */
    @Scheduled(fixedDelayString = "${dbtower.aiops.metrics-refresh-ms:15000}")
    public void refresh() {
        try {
            Map<AiOperationStatus, Long> counts = new EnumMap<>(AiOperationStatus.class);
            for (Object[] row : jobs.countByStatuses(ACTIVE)) {
                counts.put((AiOperationStatus) row[0], ((Number) row[1]).longValue());
            }
            long unpublishedCount = outbox.countUnpublished();
            long stalled = elapsedSeconds(jobs.oldestProgressAt(ACTIVE));
            for (Map.Entry<AiOperationStatus, AtomicLong> entry : active.entrySet()) {
                entry.getValue().set(counts.getOrDefault(entry.getKey(), 0L));
            }
            unpublished.set(unpublishedCount);
            stalledSeconds.set(stalled);
        } catch (RuntimeException e) {
            log.warn("AI 운영 작업 메트릭 갱신 실패 — 아무 값도 바꾸지 않는다", e);
        }
    }

    private long elapsedSeconds(OffsetDateTime since) {
        if (since == null) {
            return 0;
        }
        // 노드 간 시계 오차로 음수가 나올 수 있다 — 게이지에 음수를 남기면 그래프가 거짓말한다
        return Math.max(0, Duration.between(since, OffsetDateTime.now(clock)).toSeconds());
    }
}
