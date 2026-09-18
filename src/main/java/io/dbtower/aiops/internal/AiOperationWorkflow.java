package io.dbtower.aiops.internal;

import io.dbtower.aiops.AiOperationJobView;
import io.dbtower.aiops.AiOperationStatus;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.aiops.internal.domain.AiOperationJob;
import io.dbtower.aiops.internal.domain.AiOperationResultEntity;
import io.dbtower.aiops.internal.persistence.AiOperationJobRepository;
import io.dbtower.aiops.internal.persistence.AiOperationResultRepository;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.audit.AuditTrail;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 실행기(외부 워커)가 부르는 작업 단계 — 선점, 사실 수집, 분석·검증·완료, 실패, 알림 기록.
 *
 * <p>트랜잭션 경계가 이 클래스의 요점이다. 사실 수집(대상 DB 조회 포함)과 모델 호출은 수 초~수십 초가 걸리는데,
 * 그 동안 메타 DB 커넥션을 잡으면 풀이 작업 수만큼 묶인다(148절 재감사에서 AI 대기가 커넥션을 붙잡은 결함).
 * 그래서 상태 전이만 짧은 트랜잭션으로 끊고, 오래 걸리는 일은 트랜잭션 밖에서 한다.
 * 그 사이 작업이 취소되면 다음 전이가 표에 막혀 409가 되고, 늦게 끝난 분석 결과는 버려진다.</p>
 */
@Service
public class AiOperationWorkflow {

    /** 실행기가 참고 자료로 넘기는 과거 사례·런북 한 건. 판단 근거가 아니라 데이터다. */
    public record Reference(String id, String source, String title, String snippet, Double score) {
    }

    /** job.status가 FAILED면 선점이 아니라 범위 확인 실패다 — leaseToken은 실패를 알리는 데만 쓸 수 있다. */
    public record Claimed(AiOperationJobView job, String leaseToken, OffsetDateTime leaseUntil) {
    }

    public record Facts(AiOperationJobView job, List<String> facts, List<String> ruleFindings,
                        List<String> uncertainties) {
    }

    private static final int REFERENCE_CAP = 5;
    private static final int SNIPPET_CAP = 600;

    private final AiOperationJobRepository jobs;
    private final AiOperationResultRepository results;
    private final RegistryService registry;
    private final FactCollector collector;
    private final AiAnalyzer analyzer;
    private final AuditTrail auditTrail;
    private final JobViews views;
    private final AiOperationSettings settings;
    private final TransactionTemplate tx;
    private final AiOperationMetrics metrics;
    private final Path rulesPath;
    private final Clock clock = Clock.systemDefaultZone();

    public AiOperationWorkflow(AiOperationJobRepository jobs, AiOperationResultRepository results,
                               RegistryService registry, FactCollector collector, AiAnalyzer analyzer,
                               AuditTrail auditTrail, JobViews views, AiOperationSettings settings,
                               PlatformTransactionManager transactionManager, AiOperationMetrics metrics,
                               @Value("${dbtower.ai.rules-path:docs/design/ai-analysis-rules.md}") String rulesPath) {
        this.jobs = jobs;
        this.results = results;
        this.registry = registry;
        this.collector = collector;
        this.analyzer = analyzer;
        this.auditTrail = auditTrail;
        this.views = views;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.rulesPath = Path.of(rulesPath);
    }

    /** RECEIVED인 작업을 선점한다. 같은 이벤트가 두 번 배달되면 두 번째는 409다 — 실행기는 그 메시지를 확인 처리하고 넘어간다. */
    public Claimed claim(String jobId, String workerId) {
        return write(() -> {
            AiOperationJob job = load(jobId);
            OffsetDateTime now = now();
            // 선점 순간에 범위를 다시 확인한다 — 접수 뒤 인스턴스가 지워지거나 다른 팀으로 옮겨졌을 수 있다
            String scopeProblem = scopeProblem(job);
            if (scopeProblem != null) {
                // 예외로 끝내면 이 트랜잭션이 롤백돼 실패 기록까지 사라진다 — 실패를 커밋하고 토큰 없는 응답으로 알린다
                job.fail(scopeProblem, now);
                String notifyOnly = UUID.randomUUID().toString();
                job.grantNotificationLease(notifyOnly);
                jobs.saveAndFlush(job);
                metrics.recordFinished(job.getType(), job.getStatus(), job.getRequestedAt());
                auditTrail.record("AI 운영 작업 범위 확인 실패 jobId=" + jobId, job.getInstanceId(), 1);
                return new Claimed(views.view(job, null), notifyOnly, null);
            }
            String token = UUID.randomUUID().toString();
            job.claim(token, now, now.plus(settings.lease()));
            jobs.saveAndFlush(job);
            auditTrail.record("AI 운영 작업 선점 jobId=" + jobId + " worker=" + brief(workerId), job.getInstanceId(), 0);
            return new Claimed(views.view(job, null), token, job.getLeaseUntil());
        });
    }

    /** 유형이 정한 범위의 사실을 모아 저장한다. 수집은 트랜잭션 밖이다. */
    public Facts collect(String jobId, String token) {
        AiOperationJob job = write(() -> {
            AiOperationJob j = load(jobId);
            j.advance(token, AiOperationStatus.COLLECTING, now(), now().plus(settings.lease()));
            return jobs.saveAndFlush(j);
        });
        List<DatabaseInstance> instances = targets(job);
        FactCollector.Collected collected = collector.collect(job.getType(), job.getTrigger(), instances,
                job.getWindowFrom(), job.getWindowTo());
        return write(() -> {
            AiOperationJob j = load(jobId);
            j.requireLease(token);
            if (j.getStatus() != AiOperationStatus.COLLECTING) {
                throw new IllegalStateException("사실을 모으는 사이 작업 상태가 바뀌었습니다: " + j.getStatus());
            }
            OffsetDateTime now = now();
            String facts = views.write(collected.facts());
            String rules = views.write(collected.ruleFindings());
            String uncertain = views.write(collected.uncertainties());
            AiOperationResultEntity entity = results.findById(jobId)
                    .map(existing -> {
                        existing.recollect(facts, rules, uncertain, now);
                        return existing;
                    })
                    .orElseGet(() -> new AiOperationResultEntity(jobId, facts, rules, uncertain, now));
            results.save(entity);
            auditTrail.record("AI 운영 작업 사실 수집 jobId=" + jobId + " facts=" + collected.facts().size()
                    + " rules=" + collected.ruleFindings().size(), j.getInstanceId(), 0);
            return new Facts(views.view(j, null), collected.facts(), collected.ruleFindings(),
                    collected.uncertainties());
        });
    }

    /** 실행기가 참고 자료 검색에 들어간다는 표시. 실행기가 스스로 하는 단계는 이것 하나라 일반 전이 API를 두지 않는다. */
    public AiOperationJobView startRetrieval(String jobId, String token) {
        return write(() -> {
            AiOperationJob job = load(jobId);
            job.advance(token, AiOperationStatus.RETRIEVING, now(), now().plus(settings.lease()));
            return views.view(jobs.saveAndFlush(job), null);
        });
    }

    /**
     * 저장된 사실과 실행기가 찾은 참고 자료로 모델 소견을 받고, 사실과 대조한 뒤 완료한다.
     * AI 백엔드가 꺼져 있거나 응답 형식이 틀리면 실패가 아니라 규칙 판정만 담은 완료다 — 규칙 판정은 모델 없이도 유효하다.
     */
    public AiOperationJobView analyze(String jobId, String token, List<Reference> references) {
        record Input(AiOperationJob job, AiOperationResultEntity result) {
        }
        Input input = write(() -> {
            AiOperationJob job = load(jobId);
            AiOperationResultEntity result = results.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("사실을 먼저 수집해야 분석할 수 있습니다"));
            job.advance(token, AiOperationStatus.ANALYZING, now(), now().plus(settings.lease()));
            return new Input(jobs.saveAndFlush(job), result);
        });

        List<String> facts = views.read(input.result().getFacts());
        List<String> rules = views.read(input.result().getRuleFindings());
        List<String> collectedUncertainties = views.read(input.result().getUncertainties());
        List<String> uncertainties = new ArrayList<>(collectedUncertainties);
        List<String> referenceLines = referenceLines(references);
        String rulesText = readRules();
        String promptVersion = AnalysisPrompt.version(rulesText);
        AiOperationJob job = input.job();

        Optional<AnalysisPrompt.Parsed> parsed = Optional.empty();
        if (!analyzer.isEnabled()) {
            uncertainties.add("AI 백엔드가 꺼져 있어 규칙 판정만 제공합니다");
        } else {
            Optional<String> response = analyzer.complete(AiAnalyzer.CallSite.AIOPS, AnalysisPrompt.system(rulesText),
                    AnalysisPrompt.user(job.getType(), job.getWindowFrom(), job.getWindowTo(), facts, rules,
                            referenceLines, job.getPrompt()));
            parsed = response.flatMap(AnalysisPrompt::parse);
            if (response.isEmpty()) {
                uncertainties.add("AI 호출이 실패하거나 시간을 넘겨 규칙 판정만 제공합니다");
            } else if (parsed.isEmpty()) {
                uncertainties.add("AI 응답이 약속한 형식이 아니어서 규칙 판정만 제공합니다");
            }
        }
        List<String> evidence = parsed.map(AnalysisPrompt.Parsed::evidence).orElse(List.of());
        List<String> nextActions = new ArrayList<>(parsed.map(AnalysisPrompt.Parsed::nextActions).orElse(List.of()));
        parsed.ifPresent(p -> uncertainties.addAll(p.uncertainties()));
        String opinion = parsed.map(AnalysisPrompt.Parsed::opinion).orElse(null);
        boolean approval = parsed.map(AnalysisPrompt.Parsed::approvalRequired).orElse(false)
                || AnalysisPrompt.mentionsChange(nextActions);
        // 모델이 이미 티켓으로 가라고 적었으면 같은 안내를 한 번 더 붙이지 않는다(실측에서 두 줄이 겹쳤다)
        if (approval && nextActions.stream().noneMatch(a -> a.contains("티켓"))) {
            nextActions.add(AnalysisPrompt.TICKET_ACTION);
        }
        List<String> platformContext = new ArrayList<>(collectedUncertainties);
        platformContext.add("분석 구간 " + job.getWindowFrom() + " ~ " + job.getWindowTo());
        // 판단 기준 문서도 DBTower가 준 글이다 — 모델이 "근본 원인 5종"을 인용하자 5가 검증 안 됨으로 잡혔다(169절)
        platformContext.add(rulesText);
        List<String> unverified = ClaimVerifier.verify(opinion, evidence, nextActions, facts, rules, referenceLines,
                job.getPrompt(), platformContext);

        return write(() -> {
            AiOperationJob j = load(jobId);
            OffsetDateTime now = now();
            j.advance(token, AiOperationStatus.VERIFYING, now, now.plus(settings.lease()));
            AiOperationResultEntity result = results.findById(jobId).orElseThrow();
            result.conclude(opinion, views.write(evidence), views.write(uncertainties), views.write(nextActions),
                    views.write(referenceLines), views.write(unverified), approval, analyzer.backend(), promptVersion, now);
            j.complete(now);
            jobs.saveAndFlush(j);
            results.save(result);
            metrics.recordFinished(j.getType(), j.getStatus(), j.getRequestedAt());
            auditTrail.record("AI 운영 작업 완료 jobId=" + jobId + " opinion=" + (opinion != null)
                    + " unverified=" + unverified.size() + " approvalRequired=" + approval, j.getInstanceId(), 0);
            return views.view(j, result);
        });
    }

    public AiOperationJobView fail(String jobId, String token, String reason) {
        return write(() -> {
            AiOperationJob job = load(jobId);
            job.requireLease(token);
            job.fail(reason, now());
            jobs.saveAndFlush(job);
            metrics.recordFinished(job.getType(), job.getStatus(), job.getRequestedAt());
            auditTrail.record("AI 운영 작업 실패 jobId=" + jobId + " reason=" + brief(reason), job.getInstanceId(), 1);
            return views.view(job, null);
        });
    }

    /**
     * 알림을 보냈다고 기록한다. 이미 기록돼 있으면 alreadyNotified=true로 돌려준다 — 같은 작업이 다시 배달된 실행기는
     * 이 값을 먼저 보고 Slack에 두 번 쓰지 않는다. 종료된 작업만 알린다(진행 중 알림은 게이트웨이의 접수 응답이 한다).
     */
    public record Notified(AiOperationJobView job, boolean alreadyNotified) {
    }

    public Notified markNotified(String jobId, String token) {
        return write(() -> {
            AiOperationJob job = load(jobId);
            job.requireLease(token);
            if (!job.getStatus().terminal()) {
                throw new IllegalStateException("끝나지 않은 작업은 알림 완료로 기록하지 않습니다: " + job.getStatus());
            }
            if (job.getNotifiedAt() != null) {
                return new Notified(views.view(job, null), true);
            }
            job.markNotified(now());
            jobs.saveAndFlush(job);
            return new Notified(views.view(job, null), false);
        });
    }

    /** 실행기가 알림을 쓰기 위해 결과를 읽는다. 리스를 쥔 실행기만 — 공개 조회는 AiOperationService.find가 범위로 거른다. */
    public AiOperationJobView view(String jobId, String token) {
        return read(() -> {
            AiOperationJob job = load(jobId);
            job.requireLease(token);
            return views.view(job, results.findById(jobId).orElse(null));
        });
    }

    private List<DatabaseInstance> targets(AiOperationJob job) {
        if (job.getInstanceId() != null) {
            return registry.findOptional(job.getInstanceId()).map(List::of).orElse(List.of());
        }
        // 정기 리포트 — 실행기는 전역 토큰이라 findAll이 전부를 돌려준다. 접수 시점 범위로 직접 거른다
        return registry.findAll().stream()
                .filter(i -> Callers.inTeam(i, job.getScopeTeam()))
                .limit(FactCollector.REPORT_INSTANCE_CAP)
                .toList();
    }

    private String scopeProblem(AiOperationJob job) {
        if (job.getInstanceId() == null) {
            return job.getType() == AiOperationType.PERIODIC_REPORT ? null : "대상 인스턴스가 없는 작업입니다";
        }
        Optional<DatabaseInstance> instance = registry.findOptional(job.getInstanceId());
        if (instance.isEmpty()) {
            return "대상 인스턴스가 삭제됐습니다: id=" + job.getInstanceId();
        }
        if (!Callers.inTeam(instance.get(), job.getScopeTeam())) {
            return "대상 인스턴스가 요청자의 팀 범위를 벗어났습니다";
        }
        return null;
    }

    private List<String> referenceLines(List<Reference> references) {
        List<String> lines = new ArrayList<>();
        if (references == null) {
            return lines;
        }
        for (Reference r : references.stream().limit(REFERENCE_CAP).toList()) {
            if (r == null || r.snippet() == null || r.snippet().isBlank()) {
                continue;
            }
            String snippet = r.snippet().strip();
            if (snippet.length() > SNIPPET_CAP) {
                snippet = snippet.substring(0, SNIPPET_CAP) + "...";
            }
            lines.add("[" + brief(r.source()) + "] " + brief(r.title()) + " (" + brief(r.id()) + "): "
                    + snippet.replace('\n', ' '));
        }
        return lines;
    }

    private String readRules() {
        try {
            return Files.readString(rulesPath);
        } catch (IOException e) {
            return "";
        }
    }

    private AiOperationJob load(String jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 운영 작업을 찾을 수 없습니다"));
    }

    private <T> T write(Supplier<T> work) {
        try {
            return tx.execute(status -> work.get());
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new IllegalStateException("다른 실행기가 먼저 이 작업을 바꿨습니다");
        }
    }

    private <T> T read(Supplier<T> work) {
        TransactionTemplate readOnly = new TransactionTemplate(tx.getTransactionManager());
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> work.get());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String brief(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }
}
