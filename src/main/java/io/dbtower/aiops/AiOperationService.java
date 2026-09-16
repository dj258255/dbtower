package io.dbtower.aiops;

import io.dbtower.aiops.internal.AiOperationMetrics;
import io.dbtower.aiops.internal.AiOperationSettings;
import io.dbtower.aiops.internal.Callers;
import io.dbtower.aiops.internal.Callers.Caller;
import io.dbtower.aiops.internal.JobViews;
import io.dbtower.aiops.internal.domain.AiOperationJob;
import io.dbtower.aiops.internal.domain.AiOperationOutbox;
import io.dbtower.aiops.internal.persistence.AiOperationJobRepository;
import io.dbtower.aiops.internal.persistence.AiOperationOutboxRepository;
import io.dbtower.aiops.internal.persistence.AiOperationResultRepository;
import io.dbtower.audit.AuditTrail;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.InstanceNotFoundException;
import io.dbtower.registry.RegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 운영 작업의 접수·조회·취소·재시도 — 사람과 다른 모듈이 쓰는 공개 창구.
 * 실행기(선점·사실 수집·분석)의 단계는 internal의 AiOperationWorkflow가 맡는다.
 */
@Service
public class AiOperationService {

    private static final EnumSet<AiOperationStatus> ACTIVE = EnumSet.of(AiOperationStatus.RECEIVED,
            AiOperationStatus.AUTHORIZED, AiOperationStatus.COLLECTING, AiOperationStatus.RETRIEVING,
            AiOperationStatus.ANALYZING, AiOperationStatus.VERIFYING);
    private static final int WINDOW_MIN = 5;
    private static final int WINDOW_MAX = 7 * 24 * 60;
    static final String SUBMITTED_EVENT = "AiOperationSubmitted";

    private final AiOperationJobRepository jobs;
    private final AiOperationResultRepository results;
    private final AiOperationOutboxRepository outbox;
    private final RegistryService registry;
    private final AuditTrail auditTrail;
    private final Callers callers;
    private final JobViews views;
    private final AiOperationSettings settings;
    private final AiOperationMetrics metrics;
    private final Clock clock;

    // 생성자가 둘이라 스프링이 고를 쪽을 표시한다 — 시계 주입 생성자는 테스트가 시각을 고정하는 시임이다
    @Autowired
    public AiOperationService(AiOperationJobRepository jobs, AiOperationResultRepository results,
                              AiOperationOutboxRepository outbox, RegistryService registry, AuditTrail auditTrail,
                              Callers callers, JobViews views,
                              AiOperationSettings settings, AiOperationMetrics metrics) {
        this(jobs, results, outbox, registry, auditTrail, callers, views, settings, metrics, Clock.systemDefaultZone());
    }

    AiOperationService(AiOperationJobRepository jobs, AiOperationResultRepository results,
                       AiOperationOutboxRepository outbox, RegistryService registry, AuditTrail auditTrail,
                       Callers callers, JobViews views,
                       AiOperationSettings settings, AiOperationMetrics metrics, Clock clock) {
        this.jobs = jobs;
        this.results = results;
        this.outbox = outbox;
        this.registry = registry;
        this.auditTrail = auditTrail;
        this.callers = callers;
        this.views = views;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public AiOperationJobView submit(AiOperationRequest request) {
        Caller caller = callers.current();
        String requester;
        String team;
        if (caller.automation()) {
            // 게이트웨이·경보처럼 사람을 대신해 올리는 주체만 본문의 요청자·팀을 쓴다. 게이트웨이의 채널·사용자 허용 목록이 경계다
            if (request.requester() == null || request.requester().isBlank() || request.requester().length() > 200) {
                throw new IllegalArgumentException("자동화 주체는 대신 요청한 사람(requester)을 밝혀야 합니다");
            }
            requester = request.requester().trim();
            team = request.team() == null || request.team().isBlank() ? null : request.team().trim();
        } else {
            requester = caller.name();
            team = caller.team();
        }
        String instanceType = request.instanceId() == null ? null
                : requireInScope(request.instanceId(), caller, team).getType().name();

        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString() : request.requestId().trim();
        var existing = jobs.findByRequestId(requestId);
        if (existing.isPresent()) {
            AiOperationJob job = existing.get();
            // 멱등 재전송이면 같은 작업을 돌려준다. 남의 requestId로 조회를 떠보는 것은 막는다
            if (job.getSubmittedBy().equals(caller.name()) && job.getRequester().equals(requester)) {
                return views.view(job, null);
            }
            throw new IllegalStateException("이미 사용된 requestId입니다");
        }
        if (jobs.countByRequesterAndStatusIn(requester, ACTIVE) >= settings.maxActivePerRequester()) {
            // 모델 호출은 돈이 든다. Slack에서 같은 질문을 연달아 올려도 진행 중인 작업 수를 넘기지 못한다
            throw new IllegalStateException("진행 중인 AI 작업이 " + settings.maxActivePerRequester()
                    + "건입니다. 끝난 뒤 다시 요청하세요");
        }

        int minutes = request.windowMinutes() == null ? settings.defaultWindowMinutes()
                : Math.max(WINDOW_MIN, Math.min(WINDOW_MAX, request.windowMinutes()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        AiOperationJob job = new AiOperationJob(UUID.randomUUID().toString(), requestId, request.type(),
                request.trigger(), requester, caller.name(), team, request.instanceId(), instanceType, now.minusMinutes(minutes), now,
                request.prompt().trim(), blankToNull(request.replyChannel()), blankToNull(request.replyThread()), now);
        try {
            jobs.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("같은 requestId로 동시에 접수됐습니다. 작업 목록을 다시 조회하세요");
        }
        enqueue(job, now);
        auditTrail.record("AI 운영 작업 접수 jobId=" + job.getJobId() + " type=" + job.getType()
                + " requester=" + requester + " trigger=" + job.getTrigger(), job.getInstanceId(), 0);
        return views.view(job, null);
    }

    @Transactional(readOnly = true)
    public AiOperationJobView find(String jobId) {
        AiOperationJob job = visibleJob(jobId, callers.current());
        return views.view(job, results.findById(jobId).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<AiOperationJobView> recent(int limit) {
        Caller caller = callers.current();
        int size = Math.max(1, Math.min(100, limit));
        boolean everyone = caller.automation() || caller.global();
        return jobs.findVisible(everyone, caller.name(), caller.team(), PageRequest.of(0, size)).stream()
                .map(job -> views.view(job, null))
                .toList();
    }

    @Transactional
    public AiOperationJobView cancel(String jobId) {
        Caller caller = callers.current();
        AiOperationJob job = visibleJob(jobId, caller);
        if (!caller.automation() && !caller.operator() && !job.getRequester().equals(caller.name())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "요청자 본인이나 운영자만 취소할 수 있습니다");
        }
        job.cancel(OffsetDateTime.now(clock));
        metrics.recordFinished(job.getType(), job.getStatus(), job.getRequestedAt());
        auditTrail.record("AI 운영 작업 취소 jobId=" + jobId, job.getInstanceId(), 0);
        return views.view(job, null);
    }

    /** 실패한 작업을 새 시도로 되돌린다. 새 Outbox 이벤트가 만들어져 다시 큐로 흐른다. */
    @Transactional
    public AiOperationJobView retry(String jobId) {
        Caller caller = callers.current();
        AiOperationJob job = visibleJob(jobId, caller);
        OffsetDateTime now = OffsetDateTime.now(clock);
        job.retry(now);
        enqueue(job, now);
        auditTrail.record("AI 운영 작업 재시도 jobId=" + jobId + " attempt=" + job.getAttempt(), job.getInstanceId(), 0);
        return views.view(job, null);
    }

    /** 접수를 밖에 알리는 유일한 경로 — 같은 트랜잭션의 Outbox 한 줄이다. 모듈 이벤트를 따로 두면 전달 경로가 둘로 보인다. */
    private void enqueue(AiOperationJob job, OffsetDateTime now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String eventId = UUID.randomUUID().toString();
        payload.put("eventId", eventId);
        payload.put("jobId", job.getJobId());
        payload.put("type", job.getType().name());
        payload.put("attempt", job.getAttempt());
        outbox.save(new AiOperationOutbox(eventId, job.getJobId(), SUBMITTED_EVENT, views.write(payload), now));
    }

    private DatabaseInstance requireInScope(Long instanceId, Caller caller, String team) {
        if (!caller.automation()) {
            return registry.findById(instanceId); // 사람의 팀 범위는 레지스트리가 강제한다(범위 밖이면 미등록과 같은 404)
        }
        DatabaseInstance instance = registry.findOptional(instanceId)
                .orElseThrow(() -> new InstanceNotFoundException(instanceId));
        if (!Callers.inTeam(instance, team)) {
            throw new InstanceNotFoundException(instanceId);
        }
        return instance;
    }

    private AiOperationJob visibleJob(String jobId, Caller caller) {
        return jobs.findById(jobId).filter(job -> visible(job, caller))
                // 범위 밖은 없는 것과 같은 응답 — 다른 팀 작업의 존재를 드러내지 않는다
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 운영 작업을 찾을 수 없습니다"));
    }

    private static boolean visible(AiOperationJob job, Caller caller) {
        return caller.automation() || caller.global() || job.getRequester().equals(caller.name())
                || caller.team().equals(job.getScopeTeam());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
