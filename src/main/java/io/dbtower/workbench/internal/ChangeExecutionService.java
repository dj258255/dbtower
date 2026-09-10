package io.dbtower.workbench.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.audit.AuditTrail;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.ComparisonService.CompareResult;
import io.dbtower.insight.SchemaDiffService;
import io.dbtower.operator.ChangeCommitUncertainException;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.SqlCanonical;
import io.dbtower.operator.model.ChangeOutcome;
import io.dbtower.operator.model.ChangeOutcome.Probe;
import io.dbtower.operator.model.ChangePlan;
import io.dbtower.operator.model.ChangePlan.Kind;
import io.dbtower.operator.model.RevertPlan;
import io.dbtower.operator.model.RevertPlan.Conflict;
import io.dbtower.operator.model.RowImage;
import io.dbtower.operator.model.RowImage.ImageColumn;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.ConsoleCredentialService;
import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.dbtower.review.ChangeTicketGate;
import io.dbtower.review.ChangeTicketGate.ChangeTicket;
import io.dbtower.security.SecretCipher;
import io.dbtower.workbench.StatementClassifier;
import io.dbtower.workbench.StatementClassifier.Classification;
import io.dbtower.workbench.StatementClassifier.Tier;
import io.dbtower.workbench.internal.ChangeStatementParser.Parsed;
import io.dbtower.workbench.internal.ResultMasker.MaskedDiff;
import io.dbtower.workbench.internal.ResultMasker.Policy;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import io.dbtower.workbench.internal.domain.ChangeExecution;
import io.dbtower.workbench.internal.domain.ChangeExecution.Action;
import io.dbtower.workbench.internal.domain.ChangeExecution.Outcome;
import io.dbtower.workbench.internal.persistence.ChangeExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 승인된 변경 티켓의 실행 계층 — 드라이런, 실행, 되돌리기와 전후 비교(행·구조·실행계획·워크로드)를 한 흐름으로 묶는다.
 *
 * <p>순서가 계약이다: 티켓 상태 확인 -> 문장 재판정(분류기·단일 문장·검증 조회) -> 변경 계정 확인 -> 캡처 가능 여부 판정 ->
 * 실행권 획득(조건부 UPDATE) -> 대상 DB 실행 -> 결과 전이. 거부될 수 있는 것은 실행권을 얻기 전에 전부 거부한다 — 거부된 요청이
 * 티켓을 EXECUTING에 묶어 두지 않게. 대상 DB에 커밋된 뒤에는 기록 실패가 실행 사실을 지우지 않게 티켓 전이를 먼저 한다.
 */
@Service
public class ChangeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ChangeExecutionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DIFF_ROWS_MAX = 200;

    private final ChangeTicketGate gate;
    private final RegistryService registry;
    private final ConsoleCredentialService credentials;
    private final DbmsOperatorFactory operators;
    private final ChangeExecutionRepository executions;
    private final WorkbenchService workbench;
    private final SchemaDiffService schemaDiff;
    private final ComparisonService comparison;
    private final SecretCipher cipher;
    private final AuditTrail audit;
    private final int maxRows;
    private final int timeoutSeconds;
    private final int retentionDays;

    public ChangeExecutionService(ChangeTicketGate gate, RegistryService registry, ConsoleCredentialService credentials,
                                  DbmsOperatorFactory operators, ChangeExecutionRepository executions,
                                  WorkbenchService workbench, SchemaDiffService schemaDiff, ComparisonService comparison,
                                  SecretCipher cipher, AuditTrail audit,
                                  @Value("${dbtower.workbench.change.max-rows:10000}") int maxRows,
                                  @Value("${dbtower.workbench.change.timeout-seconds:30}") int timeoutSeconds,
                                  @Value("${dbtower.workbench.change.image-retention-days:7}") int retentionDays) {
        this.gate = gate;
        this.registry = registry;
        this.credentials = credentials;
        this.operators = operators;
        this.executions = executions;
        this.workbench = workbench;
        this.schemaDiff = schemaDiff;
        this.comparison = comparison;
        this.cipher = cipher;
        this.audit = audit;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
        this.retentionDays = retentionDays;
    }

    /**
     * @param rowChanges 행 사본 전후 비교(마스킹 후). 사본이 없으면 null
     * @param schemaDiff DDL 전후 구조 diff(없으면 null)
     * @param probe      검증 조회의 전후 실행계획·응답시간(없으면 null)
     * @param detail     실패 사유 문자열, 또는 충돌 목록
     */
    public record ExecutionView(Long id, Long reviewId, String action, String kind, String outcome, String tableName,
                                Long affectedRows, boolean rollbackAvailable, String rollbackNote, boolean imagesEncrypted,
                                LocalDateTime imagesExpireAt, RowChanges rowChanges, Object schemaDiff, Object probe,
                                Object detail, String statementSha256, String principal,
                                LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** 값은 마스킹 규칙을 거친 뒤다. 바뀌었는지(changed) 판정은 원래 값으로 했다 */
    public record RowChanges(RowDiff.Result diff, List<String> maskedColumns, String unavailable) {
    }

    public record ProbeView(String beforePlan, String afterPlan, List<Long> beforeMicros, List<Long> afterMicros,
                            Long beforeMedianMicros, Long afterMedianMicros, boolean planChanged,
                            String beforeError, String afterError) {
    }

    public record WorkloadView(LocalDateTime baseFrom, LocalDateTime baseTo, LocalDateTime targetFrom,
                               LocalDateTime targetTo, CompareResult result, String note) {
    }

    record Images(RowImage before, RowImage after) {
    }

    private record Prepared(DbmsOperator operator, ConsoleCredential credential, ChangePlan plan, String sha256) {
    }

    public ExecutionView dryRun(Long reviewId, boolean withoutCapture) {
        ChangeTicket ticket = gate.ticket(reviewId);
        if (!"PENDING".equals(ticket.status()) && !"APPROVED".equals(ticket.status())) {
            throw new WorkbenchRejection(409, "드라이런은 대기·승인 상태 티켓만 할 수 있습니다(현재 " + ticket.status() + ")", null);
        }
        Prepared p = prepare(ticket, true, withoutCapture);
        ChangeExecution record = executions.save(start(ticket, p, Action.DRY_RUN));
        try {
            ChangeOutcome outcome = p.operator().executeChange(p.credential(), p.plan());
            record.finish(Outcome.ROLLED_BACK, outcome.affectedRows(), null);
            attach(record, outcome, false);
            audit.record("CHANGE_DRY_RUN review=" + reviewId, ticket.instanceId(), 200);
        } catch (OperatorException | UnsupportedOperationException e) {
            record.finish(Outcome.FAILED, null, e.getMessage());
            executions.save(record);
            audit.record("CHANGE_DRY_RUN review=" + reviewId, ticket.instanceId(), 422);
            throw new WorkbenchRejection(422, e.getMessage(), null);
        }
        return view(executions.save(record));
    }

    public ExecutionView execute(Long reviewId, boolean withoutCapture) {
        ChangeTicket ticket = gate.ticket(reviewId);
        if (!"APPROVED".equals(ticket.status())) {
            throw new WorkbenchRejection(409, "승인된 티켓만 실행합니다(현재 " + ticket.status() + ")", null);
        }
        Prepared p = prepare(ticket, false, withoutCapture);
        SchemaSnapshot schemaBefore = p.plan().kind() == Kind.DDL ? trySchema(p.operator()) : null;
        if (!gate.claimExecution(reviewId)) {
            throw new WorkbenchRejection(409, "다른 요청이 이 티켓을 이미 실행 중이거나 상태가 바뀌었습니다", null);
        }
        ChangeExecution record;
        try {
            record = executions.save(start(ticket, p, Action.EXECUTE));
        } catch (RuntimeException e) {
            gate.releaseExecution(reviewId);
            throw e;
        }
        ChangeOutcome outcome;
        try {
            outcome = p.operator().executeChange(p.credential(), p.plan());
        } catch (ChangeCommitUncertainException e) {
            record.finish(Outcome.UNCERTAIN, null, e.getMessage());
            executions.save(record);
            audit.record("CHANGE_EXECUTE review=" + reviewId, ticket.instanceId(), 500);
            // 실행권을 돌려주지 않는다 — 반영 여부를 사람이 확인하기 전까지 같은 변경이 두 번 나가지 않게
            throw new WorkbenchRejection(500, e.getMessage() + " 티켓은 EXECUTING으로 남겨 재실행을 막았습니다", null);
        } catch (OperatorException | UnsupportedOperationException e) {
            record.finish(Outcome.FAILED, null, e.getMessage());
            executions.save(record);
            gate.releaseExecution(reviewId);
            audit.record("CHANGE_EXECUTE review=" + reviewId, ticket.instanceId(), 422);
            throw new WorkbenchRejection(422, e.getMessage(), null);
        }
        gate.completeExecution(reviewId, ticket.instanceId(), principal(), outcome.affectedRows());
        record.finish(Outcome.COMMITTED, outcome.affectedRows(), null);
        try {
            attach(record, outcome, true);
            if (schemaBefore != null) {
                SchemaSnapshot schemaAfter = trySchema(p.operator());
                if (schemaAfter != null) {
                    record.attachSchemaDiff(write(schemaDiff.diff(schemaBefore, schemaAfter)));
                }
            }
        } catch (RuntimeException e) {
            // 커밋은 이미 끝났다 — 사본·비교 기록 실패가 실행 기록 자체를 잃게 하지 않는다
            log.error("변경 실행 뒤 부가 기록 실패 review={}", reviewId, e);
            record.closeRollback("실행 뒤 기록 중 오류로 행 사본을 남기지 못했다: " + e.getMessage());
        }
        ExecutionView view = view(executions.save(record));
        audit.record("CHANGE_EXECUTE review=" + reviewId, ticket.instanceId(), 200);
        return view;
    }

    public ExecutionView revert(Long reviewId, boolean dryRun) {
        ChangeTicket ticket = gate.ticket(reviewId);
        if (!"EXECUTED".equals(ticket.status())) {
            throw new WorkbenchRejection(409, "실행 완료된 티켓만 되돌립니다(현재 " + ticket.status() + ")", null);
        }
        ChangeExecution executed = executions
                .findFirstByReviewIdAndActionAndOutcomeOrderByStartedAtDesc(reviewId, Action.EXECUTE, Outcome.COMMITTED)
                .orElseThrow(() -> new WorkbenchRejection(409, "커밋된 실행 기록이 없습니다", null));
        if (!executed.isRollbackAvailable() || executed.getImages() == null) {
            throw new WorkbenchRejection(409, "되돌리기 경로가 없습니다: " + executed.getRollbackNote(), null);
        }
        Images images = readImages(executed);
        DatabaseInstance instance = registry.findById(ticket.instanceId());
        ConsoleCredential credential = writeCredential(ticket.instanceId());
        RevertPlan plan = new RevertPlan(Kind.valueOf(executed.getKind()), executed.getTableName(),
                images.before(), images.after(), timeoutSeconds, dryRun);
        if (!dryRun && !gate.claimRollback(reviewId)) {
            throw new WorkbenchRejection(409, "다른 요청이 이 티켓을 이미 되돌리는 중이거나 상태가 바뀌었습니다", null);
        }
        String action = dryRun ? "CHANGE_REVERT_DRY_RUN review=" : "CHANGE_REVERT review=";
        ChangeExecution record = executions.save(new ChangeExecution(reviewId, ticket.instanceId(),
                dryRun ? Action.REVERT_DRY_RUN : Action.REVERT, executed.getKind(), executed.getTableName(),
                executed.getStatementSha256(), principal()));
        try {
            RevertPlan.Outcome result = operators.create(instance).revertChange(credential, plan);
            if (!result.conflicts().isEmpty()) {
                RowImage reference = plan.originalKind() == Kind.DELETE ? images.before() : images.after();
                record.finish(Outcome.CONFLICT, 0L, write(maskConflicts(result.conflicts(), reference,
                        workbench.policies(ticket.instanceId()))));
                if (!dryRun) {
                    gate.releaseRollback(reviewId);
                }
                audit.record(action + reviewId, ticket.instanceId(), 409);
            } else {
                record.finish(dryRun ? Outcome.ROLLED_BACK : Outcome.COMMITTED, result.restoredRows(), null);
                if (!dryRun) {
                    gate.completeRollback(reviewId, ticket.instanceId(), principal(), result.restoredRows());
                    // 원래 실행 기록이 "되돌리기 가능"으로 남으면 화면이 게이트가 거부할 버튼을 보여준다(라이브 검증에서 발견)
                    executed.markReverted("되돌렸다(" + principal() + ", " + LocalDateTime.now().withNano(0) + ")");
                    executions.save(executed);
                }
                audit.record(action + reviewId, ticket.instanceId(), 200);
            }
        } catch (ChangeCommitUncertainException e) {
            record.finish(Outcome.UNCERTAIN, null, e.getMessage());
            executions.save(record);
            audit.record(action + reviewId, ticket.instanceId(), 500);
            throw new WorkbenchRejection(500, e.getMessage() + " 티켓은 ROLLING_BACK으로 남겨 재시도를 막았습니다", null);
        } catch (OperatorException | UnsupportedOperationException e) {
            record.finish(Outcome.FAILED, null, e.getMessage());
            executions.save(record);
            if (!dryRun) {
                gate.releaseRollback(reviewId);
            }
            audit.record(action + reviewId, ticket.instanceId(), 422);
            throw new WorkbenchRejection(422, e.getMessage(), null);
        }
        return view(executions.save(record));
    }

    public List<ExecutionView> executions(Long reviewId) {
        gate.ticket(reviewId);
        return executions.findByReviewIdOrderByStartedAtDesc(reviewId).stream().map(this::view).toList();
    }

    /**
     * 실행 시각을 기준으로 앞뒤 같은 길이의 구간을 insight 스냅샷으로 비교한다. 행·구조·계획 비교가 "이 변경이 무엇을 바꿨나"라면,
     * 이것은 "그 뒤 실제 트래픽에서 무엇이 달라졌나"다. 뒤 구간이 아직 다 지나지 않았으면 지난 만큼만 비교하고 그렇다고 적는다.
     */
    public WorkloadView workload(Long executionId, int windowMinutes) {
        ChangeExecution e = executions.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("실행 기록을 찾을 수 없습니다: " + executionId));
        gate.ticket(e.getReviewId());
        if (e.getAction() != Action.EXECUTE || e.getOutcome() != Outcome.COMMITTED || e.getFinishedAt() == null) {
            throw new WorkbenchRejection(409, "커밋된 실행에만 전후 워크로드 비교가 있습니다", null);
        }
        int minutes = Math.clamp(windowMinutes, 5, 24 * 60);
        LocalDateTime pivot = e.getFinishedAt();
        LocalDateTime wanted = pivot.plusMinutes(minutes);
        LocalDateTime targetTo = wanted.isAfter(LocalDateTime.now()) ? LocalDateTime.now() : wanted;
        String note = targetTo.isBefore(wanted)
                ? "실행 뒤 구간이 아직 다 지나지 않아 " + Duration.between(pivot, targetTo).toMinutes()
                + "분만 비교했다. 스냅샷 수집 주기보다 짧으면 뒤 구간이 비어 있을 수 있다"
                : null;
        try {
            return new WorkloadView(pivot.minusMinutes(minutes), pivot, pivot, targetTo,
                    comparison.compare(e.getInstanceId(), pivot.minusMinutes(minutes), pivot, pivot, targetTo), note);
        } catch (IllegalArgumentException notEnough) {
            // 실행 직후에는 뒤 구간에 스냅샷 배치가 아직 없다 — 오류가 아니라 "아직 비교할 재료가 없다"는 상태로 돌려준다(라이브 검증에서 400으로 보였다)
            return new WorkloadView(pivot.minusMinutes(minutes), pivot, pivot, targetTo, null,
                    "비교할 스냅샷이 아직 부족하다. 수집 주기가 지난 뒤 다시 보라: " + notEnough.getMessage());
        }
    }

    private Prepared prepare(ChangeTicket ticket, boolean dryRun, boolean withoutCapture) {
        DatabaseInstance instance = registry.findById(ticket.instanceId());
        String sql = ticket.sql();
        if (SqlCanonical.hasStatementSeparator(SqlCanonical.canonical(sql))) {
            throw new WorkbenchRejection(422, "변경 티켓은 한 문장만 실행합니다. 문장마다 티켓을 나눠 올리세요", null);
        }
        // 승인은 사람이 했지만 분류기는 실행 직전에 다시 판정한다 — 승인이 차단 목록을 우회하는 통로가 되지 않게
        Classification c = StatementClassifier.classify(sql);
        if (c.tier() == Tier.BLOCKED) {
            throw new WorkbenchRejection(422, "차단 문장은 승인돼도 실행하지 않습니다: " + c.reason(), c);
        }
        if (c.tier() == Tier.READ) {
            throw new WorkbenchRejection(422, "조회 문장은 티켓이 아니라 워크벤치에서 바로 실행합니다", c);
        }
        if (c.kind().startsWith("MONGO_")) {
            throw new WorkbenchRejection(422, "MongoDB 변경 티켓 실행은 아직 지원하지 않습니다", c);
        }
        String probe = null;
        if (ticket.verifySql() != null && !ticket.verifySql().isBlank()) {
            Classification pc = StatementClassifier.classify(ticket.verifySql());
            if (pc.tier() != Tier.READ || pc.kind().startsWith("MONGO_")) {
                throw new WorkbenchRejection(422, "검증 조회는 SQL 읽기 문장이어야 합니다: " + pc.reason(), pc);
            }
            probe = ticket.verifySql();
        }
        ConsoleCredential credential = writeCredential(ticket.instanceId());
        Parsed parsed = ChangeStatementParser.parse(sql);
        Kind kind = parsed.kind();
        if (kind == Kind.UNCAPTURED && !withoutCapture) {
            throw new WorkbenchRejection(409, "행 사본을 잡을 수 없는 문장입니다: " + parsed.reason()
                    + ". 되돌리기 경로 없이 실행하려면 캡처 없이 실행을 명시하세요", c);
        }
        if (withoutCapture && kind != Kind.DDL) {
            kind = Kind.UNCAPTURED;
        }
        boolean captured = kind == Kind.UPDATE || kind == Kind.DELETE || kind == Kind.INSERT;
        ChangePlan plan = new ChangePlan(kind, sql, captured ? parsed.table() : null, captured ? parsed.captureSql() : null,
                probe, maxRows, timeoutSeconds, dryRun);
        return new Prepared(operators.create(instance), credential, plan, sha256(sql));
    }

    private ConsoleCredential writeCredential(Long instanceId) {
        return credentials.find(instanceId, CredentialPurpose.WRITE).orElseThrow(() -> new WorkbenchRejection(409,
                "이 인스턴스에는 변경 계정(WRITE)이 없습니다. ADMIN이 콘솔 계정을 등록해야 승인 티켓을 실행할 수 있습니다", null));
    }

    private ChangeExecution start(ChangeTicket ticket, Prepared p, Action action) {
        return new ChangeExecution(ticket.id(), ticket.instanceId(), action, p.plan().kind().name(), p.plan().table(),
                p.sha256(), principal());
    }

    private void attach(ChangeExecution record, ChangeOutcome outcome, boolean committed) {
        boolean restorable = outcome.rollbackUnavailable() == null && outcome.before() != null && outcome.after() != null;
        if (outcome.before() != null || outcome.after() != null) {
            String json = write(new Images(outcome.before(), outcome.after()));
            boolean encrypt = cipher.enabled();
            record.attachImages(encrypt ? cipher.encrypt(json) : json, encrypt,
                    LocalDateTime.now().plusDays(retentionDays), restorable, outcome.rollbackUnavailable());
        } else {
            record.closeRollback(outcome.rollbackUnavailable());
        }
        if (outcome.probeBefore() != null || outcome.probeAfter() != null) {
            record.attachProbe(write(probeView(outcome.probeBefore(), outcome.probeAfter())));
        }
    }

    static ProbeView probeView(Probe before, Probe after) {
        String beforePlan = before == null ? null : before.plan();
        String afterPlan = after == null ? null : after.plan();
        boolean changed = beforePlan != null && afterPlan != null && !planShape(beforePlan).equals(planShape(afterPlan));
        return new ProbeView(beforePlan, afterPlan,
                before == null ? List.of() : before.timingsMicros(), after == null ? List.of() : after.timingsMicros(),
                median(before), median(after), changed,
                before == null ? null : before.error(), after == null ? null : after.error());
    }

    /** 계획의 모양만 남긴다 — 비용·예상 행 수 같은 숫자는 데이터가 조금만 바뀌어도 달라져 "계획이 바뀌었다"를 오판하게 한다 */
    static String planShape(String plan) {
        return plan.replaceAll("\\d+(\\.\\d+)?", "#").replaceAll("\\s+", " ").strip();
    }

    private static Long median(Probe probe) {
        if (probe == null || probe.timingsMicros() == null || probe.timingsMicros().isEmpty()) {
            return null;
        }
        List<Long> sorted = probe.timingsMicros().stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private ExecutionView view(ChangeExecution e) {
        Object detail = e.getOutcome() == Outcome.CONFLICT ? readTree(e.getDetail()) : e.getDetail();
        return new ExecutionView(e.getId(), e.getReviewId(), e.getAction().name(), e.getKind(), e.getOutcome().name(),
                e.getTableName(), e.getAffectedRows(), e.isRollbackAvailable(), e.getRollbackNote(), e.isImagesEncrypted(),
                e.getImagesExpireAt(), e.getImages() == null ? null : rowChanges(e), readTree(e.getSchemaDiff()),
                readTree(e.getProbe()), detail, e.getStatementSha256(), e.getPrincipal(), e.getStartedAt(), e.getFinishedAt());
    }

    private RowChanges rowChanges(ChangeExecution e) {
        Images images;
        try {
            images = readImages(e);
        } catch (WorkbenchRejection ex) {
            return new RowChanges(null, List.of(), ex.getMessage());
        }
        if (images.before() == null || images.after() == null) {
            return new RowChanges(null, List.of(), "변경 전후 사본이 한쪽만 있어 행 비교를 만들 수 없다: " + e.getRollbackNote());
        }
        List<String> beforeColumns = images.before().columns().stream().map(ImageColumn::name).toList();
        List<String> afterColumns = images.after().columns().stream().map(ImageColumn::name).toList();
        RowImage keyed = images.before().keyColumns().isEmpty() ? images.after() : images.before();
        RowDiff.Result diff = RowDiff.diff(beforeColumns, objects(images.before()), afterColumns, objects(images.after()),
                keyed.keyColumns(), DIFF_ROWS_MAX);
        MaskedDiff masked = ResultMasker.maskDiff(diff, workbench.policies(e.getInstanceId()));
        return new RowChanges(masked.diff(), masked.maskedColumns(), null);
    }

    private static List<List<Object>> objects(RowImage image) {
        return image.rows().stream().map(row -> (List<Object>) new ArrayList<Object>(row)).toList();
    }

    /** 충돌 목록에는 키 값만 싣는다 — 키 열이 마스킹 대상이면(이메일이 키인 표 등) 키 값도 가린다 */
    private static List<Conflict> maskConflicts(List<Conflict> conflicts, RowImage reference, List<Policy> policies) {
        List<Conflict> out = new ArrayList<>(conflicts.size());
        for (Conflict conflict : conflicts) {
            List<String> key = new ArrayList<>(conflict.keyValues().size());
            for (int i = 0; i < conflict.keyValues().size(); i++) {
                var strategy = ResultMasker.strategyFor(reference.keyColumns().get(i), policies);
                String value = conflict.keyValues().get(i);
                key.add(strategy == null || value == null ? value : String.valueOf(ResultMasker.mask(value, strategy)));
            }
            out.add(new Conflict(key, conflict.changedColumns(), conflict.reason()));
        }
        return out;
    }

    private Images readImages(ChangeExecution e) {
        String payload = e.getImages();
        if (e.isImagesEncrypted()) {
            if (!cipher.enabled()) {
                throw new WorkbenchRejection(409, "행 사본이 암호화돼 있는데 지금 암호화 키가 없어 읽을 수 없습니다", null);
            }
            payload = cipher.decrypt(payload);
        }
        try {
            return JSON.readValue(payload, Images.class);
        } catch (JsonProcessingException ex) {
            throw new WorkbenchRejection(409, "행 사본을 읽지 못했습니다: " + ex.getOriginalMessage(), null);
        }
    }

    private SchemaSnapshot trySchema(DbmsOperator operator) {
        try {
            return operator.describeSchema();
        } catch (RuntimeException e) {
            // 구조 비교는 부가 정보다 — 스키마 조회 실패가 승인된 변경의 실행을 막지 않는다
            log.warn("변경 전후 스키마 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("기록 직렬화 실패: " + e.getOriginalMessage(), e);
        }
    }

    /** HTTP 응답 쪽 Jackson 버전에 기대지 않게 저장된 JSON을 Map·List로 되돌려 싣는다 */
    private static Object readTree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    static String sha256(String sql) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }
}
