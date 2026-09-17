package io.dbtower.workbench.internal;

import io.dbtower.audit.AuditTrail;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.ConsoleCredentialService;
import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.dbtower.operator.model.BulkBatchOutcome;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.review.ChangeTicketGate;
import io.dbtower.review.ChangeTicketGate.ChangeTicket;
import io.dbtower.workbench.internal.domain.BulkChangeBatch;
import io.dbtower.workbench.internal.domain.BulkChangeRunRecord;
import io.dbtower.workbench.internal.persistence.BulkChangeBatchRepository;
import io.dbtower.workbench.internal.persistence.BulkChangeRunRepository;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 대량 일괄 변경 — 승인된 티켓 하나를 배치로 쪼개 실행한다(docs/bulk-change-spec.md).
 *
 * <p>티켓의 권위는 그대로 {@link ChangeTicketGate}에 있다. 이 서비스는 실행권을 조건부 UPDATE로 얻고
 * ({@code APPROVED -> EXECUTING}) 배치 진행 상태만 따로 들고 있다가, 끝나면 게이트에 결과를 돌려준다.
 *
 * <p>왜 실행을 별도 스레드에 두는가: 대량 변경은 수 분~수 시간 걸린다. 요청 스레드를 붙잡으면 웹 요청이 타임아웃되고,
 * 그 타임아웃이 실행을 끊지도 못한다(이미 커밋된 배치는 남는다). 시작은 즉시 돌려주고, 진행은 조회로 본다.
 *
 * <p>실패해도 실행권을 돌려주지 않는다. 소량 변경은 대상 DB가 롤백을 확정하면 재실행이 안전하지만, 여기서는
 * 앞선 배치가 이미 커밋돼 있어 "처음부터 다시"가 성립하지 않는다. 사람이 마지막 키를 보고 판단해야 한다.
 */
@Service
public class BulkChangeService {

    private static final Logger log = LoggerFactory.getLogger(BulkChangeService.class);

    /** 진행 중인 실행 — 티켓 하나에 하나. 실행권 자체는 게이트의 조건부 UPDATE가 지킨다. */
    private final Map<Long, BulkChangeRun> running = new ConcurrentHashMap<>();

    private final ChangeTicketGate gate;
    private final RegistryService registry;
    private final ConsoleCredentialService credentials;
    private final DbmsOperatorFactory operators;
    private final BackupFreshnessService backups;
    private final AuditTrail audit;
    private final BulkChangeRunRepository runs;
    private final BulkChangeBatchRepository batchRecords;
    private final ExecutorService executor;
    private final int batchRows;
    private final int timeoutSeconds;
    private final long pauseMillis;
    private final double lagThresholdSecond;

    public BulkChangeService(ChangeTicketGate gate, RegistryService registry, ConsoleCredentialService credentials,
                             DbmsOperatorFactory operators, BackupFreshnessService backups, AuditTrail audit,
                             BulkChangeRunRepository runs, BulkChangeBatchRepository batchRecords,
                             @Value("${dbtower.workbench.bulk.batch-rows:1000}") int batchRows,
                             @Value("${dbtower.workbench.bulk.timeout-seconds:30}") int timeoutSeconds,
                             @Value("${dbtower.workbench.bulk.pause-millis:100}") long pauseMillis,
                             @Value("${dbtower.workbench.bulk.lag-threshold-seconds:5}") double lagThresholdSecond) {
        this.gate = gate;
        this.registry = registry;
        this.credentials = credentials;
        this.operators = operators;
        this.backups = backups;
        this.audit = audit;
        this.runs = runs;
        this.batchRecords = batchRecords;
        this.batchRows = batchRows;
        this.timeoutSeconds = timeoutSeconds;
        this.pauseMillis = pauseMillis;
        this.lagThresholdSecond = lagThresholdSecond;
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("bulk-change-", 0).factory());
    }

    /** 실행 중인 한 건의 겉보기 — 화면·조회가 쓴다(#104에서 배치 기록이 붙는다). */
    public record RunView(Long reviewId, String state, Object lastAppliedKey, long affectedRows, int batches) {
    }

    /**
     * 승인된 티켓을 배치로 실행하기 시작한다. 실행 전 조건을 모두 통과해야 실행권을 가져간다 —
     * 조건 검사에서 거부되면 티켓은 APPROVED로 남아 다시 시도할 수 있다.
     *
     * @param approvedRows 승인 시점에 티켓에 고정한 예상 영향 행 수(없으면 0)
     */
    public RunView start(Long reviewId, long approvedRows) {
        ChangeTicket ticket = gate.ticket(reviewId);
        if (!"APPROVED".equals(ticket.status())) {
            throw new WorkbenchRejection(409, "승인된 티켓만 실행합니다(현재 " + ticket.status() + ")", null);
        }
        DatabaseInstance instance = registry.findById(ticket.instanceId());
        DbmsOperator operator = operators.create(instance);
        ConsoleCredential credential = credentials.find(ticket.instanceId(), CredentialPurpose.WRITE)
                .orElseThrow(() -> new WorkbenchRejection(409,
                        "이 인스턴스에는 변경 계정(WRITE)이 없습니다. ADMIN이 콘솔 계정을 등록해야 실행할 수 있습니다", null));

        // 조건은 실행권을 가져가기 전에 본다 — 거부된 티켓이 EXECUTING에 갇히지 않게
        BulkChangePlan plan = BulkChangePreflight.plan(instance.getType(), ticket.sql(), operator, credential,
                backups.freshnessFor(instance), batchRows, timeoutSeconds, approvedRows);

        if (!gate.claimExecution(reviewId)) {
            throw new WorkbenchRejection(409, "다른 요청이 이 티켓을 이미 실행 중이거나 상태가 바뀌었습니다", null);
        }
        runs.save(new BulkChangeRunRecord(reviewId, ticket.instanceId(), plan.table(), plan.keyColumn(),
                batchRows, LocalDateTime.now()));
        BulkChangeRun[] holder = new BulkChangeRun[1];
        BulkChangeRun run = new BulkChangeRun(operator, credential, plan,
                new BulkChangeRun.Policy(pauseMillis, lagThresholdSecond, 1000, 1_000_000),
                new BulkChangeRun.Listener() {
                    @Override
                    public void batch(BulkBatchOutcome outcome, ReplicationState lag) {
                        recordBatch(reviewId, ticket.instanceId(), holder[0], outcome, lag);
                    }

                    @Override
                    public void state(BulkChangeRun.State state, String reason) {
                        log.info("대량 일괄 변경 상태 review={} state={} 사유={}", reviewId, state, reason);
                        recordState(reviewId, holder[0], state, reason);
                    }
                }, Thread::sleep);
        holder[0] = run;
        running.put(reviewId, run);
        audit.record("BULK_CHANGE_START review=" + reviewId + " table=" + plan.table(), ticket.instanceId(), 200);

        executor.execute(() -> finish(reviewId, ticket, run));
        return view(reviewId, run);
    }

    private void finish(Long reviewId, ChangeTicket ticket, BulkChangeRun run) {
        BulkChangeRun.State end = run.run();
        if (end == BulkChangeRun.State.DONE) {
            gate.completeExecution(reviewId, ticket.instanceId(), ticket.requester(), run.affectedRows());
        }
        // DONE이 아니면 실행권을 돌려주지 않는다 — 앞선 배치가 이미 커밋돼 "처음부터 다시"가 성립하지 않는다
        audit.record("BULK_CHANGE_END review=" + reviewId + " state=" + end
                + " rows=" + run.affectedRows() + " lastKey=" + run.lastAppliedKey(),
                ticket.instanceId(), end == BulkChangeRun.State.DONE ? 200 : 409);
    }

    /**
     * 배치 하나를 기록한다. 기록이 실패해도 실행은 멈추지 않는다 — 대상 DB에는 이미 커밋됐고, 기록을 못 남겼다고
     * 진행을 끊으면 "어디까지 적용됐나"가 더 흐려진다. 대신 로그로 남겨 사람이 알 수 있게 한다.
     */
    private void recordBatch(Long reviewId, Long instanceId, BulkChangeRun run, BulkBatchOutcome outcome,
                             ReplicationState lag) {
        try {
            batchRecords.save(new BulkChangeBatch(reviewId, instanceId, run.batches(),
                    outcome.fromKey() == null ? null : String.valueOf(outcome.fromKey()),
                    String.valueOf(outcome.toKey()), outcome.affectedRows(), outcome.elapsedMillis(),
                    lag == null ? null : lag.lagSeconds(), lag == null ? null : lag.lagSource().name(),
                    LocalDateTime.now()));
            updateRun(reviewId, run, run.state().name(), null);
        } catch (RuntimeException e) {
            log.error("대량 일괄 변경 배치 기록 실패 review={} batch={}", reviewId, run.batches(), e);
        }
    }

    private void recordState(Long reviewId, BulkChangeRun run, BulkChangeRun.State state, String reason) {
        try {
            updateRun(reviewId, run, state.name(), reason);
        } catch (RuntimeException e) {
            log.error("대량 일괄 변경 상태 기록 실패 review={} state={}", reviewId, state, e);
        }
    }

    private void updateRun(Long reviewId, BulkChangeRun run, String state, String reason) {
        runs.findById(reviewId).ifPresent(record -> {
            record.progress(state, reason,
                    run.lastAppliedKey() == null ? null : String.valueOf(run.lastAppliedKey()),
                    run.affectedRows(), run.batches(), LocalDateTime.now());
            runs.save(record);
        });
    }

    /** 배치별 기록 — 화면은 마지막 50개만 받는다(배치가 수천 개면 전부 내려보내는 것이 곧 사고다). */
    public List<BulkChangeBatch> recentBatches(Long reviewId) {
        gate.ticket(reviewId);   // 팀 범위 밖 티켓이면 여기서 끊긴다
        return batchRecords.findTop50ByReviewIdOrderByBatchNoDesc(reviewId);
    }

    public RunView status(Long reviewId) {
        gate.ticket(reviewId);   // 팀 범위 밖 티켓이면 여기서 끊긴다
        BulkChangeRun run = running.get(reviewId);
        if (run != null) {
            return view(reviewId, run);
        }
        // 메모리에 없으면 표를 읽는다 — 앱이 실행 중에 죽어도 커밋된 배치는 대상에 남아 있다
        return runs.findById(reviewId)
                .map(r -> new RunView(reviewId, r.getState(), r.getLastKey(), r.getAffectedRows(), r.getBatches()))
                .orElseThrow(() -> new WorkbenchRejection(404, "대량 일괄 변경 기록이 없습니다", null));
    }

    public RunView pause(Long reviewId) {
        return control(reviewId, BulkChangeRun::pause, "BULK_CHANGE_PAUSE");
    }

    public RunView resume(Long reviewId) {
        return control(reviewId, BulkChangeRun::resume, "BULK_CHANGE_RESUME");
    }

    public RunView cancel(Long reviewId) {
        return control(reviewId, BulkChangeRun::cancel, "BULK_CHANGE_CANCEL");
    }

    private RunView control(Long reviewId, java.util.function.Consumer<BulkChangeRun> action, String auditAction) {
        ChangeTicket ticket = gate.ticket(reviewId);
        BulkChangeRun run = running.get(reviewId);
        if (run == null) {
            throw new WorkbenchRejection(404, "진행 중인 대량 일괄 변경이 없습니다", null);
        }
        action.accept(run);
        audit.record(auditAction + " review=" + reviewId, ticket.instanceId(), 200);
        return view(reviewId, run);
    }

    private static RunView view(Long reviewId, BulkChangeRun run) {
        return new RunView(reviewId, run.state().name(), run.lastAppliedKey(), run.affectedRows(), run.batches());
    }
}
