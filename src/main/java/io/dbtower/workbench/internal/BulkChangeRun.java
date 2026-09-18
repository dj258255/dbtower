package io.dbtower.workbench.internal;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.BulkBatchOutcome;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.registry.ConsoleCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 대량 일괄 변경 한 건의 진행 — 배치를 순서대로 돌리고, 복제가 밀리면 멈췄다 재개하고, 사람이 멈추거나 취소하면 따른다
 * (docs/bulk-change-spec.md).
 *
 * <p>왜 배치가 끝난 <b>뒤에</b> 멈추는가: 일시정지·취소가 실행 중인 문장을 죽이지 않는다. 문장을 끊으면 그 배치는
 * 롤백되지만 대상 DB는 이미 그만큼 일을 했고, 큰 구간일수록 롤백이 더 비싸다. 배치 경계에서 멈추면 "어디까지 적용됐나"가
 * 마지막 키 하나로 정확히 남는다.
 *
 * <p>취소는 이미 커밋한 배치를 되돌리지 않는다. 대량 변경은 부분 적용이 정상 상태이고, 절반만 되돌리는 쪽이 더 위험하다.
 * 되돌릴지는 사람이 마지막 키와 백업을 보고 판단한다.
 *
 * <p>복제 지연은 {@code lagSource == MEASURED}일 때만 멈춤 사유가 된다. 못 재는 기종과 지금 못 읽은 상태를
 * "지연 0"으로 읽으면, 실제로는 밀리고 있는 복제본을 보고도 계속 밀어붙이게 된다(ReplicationState 주석의 규율).
 */
public class BulkChangeRun {

    private static final Logger log = LoggerFactory.getLogger(BulkChangeRun.class);

    public enum State { RUNNING, PAUSED_LAG, PAUSED_BY_USER, CANCELLED, DONE, FAILED }

    /**
     * @param pauseMillis        배치 사이 쉬는 간격 — 복제·purge가 따라올 시간을 준다
     * @param lagThresholdSecond 이 값을 넘는 실측 복제 지연이면 멈춘다
     * @param lagPollMillis      멈춘 뒤 다시 지연을 확인하는 간격
     * @param maxBatches         안전판 — 이 수를 넘기면 멈춘다(경계가 전진하지 않는 상황을 무한히 돌지 않게)
     */
    public record Policy(long pauseMillis, double lagThresholdSecond, long lagPollMillis, int maxBatches) {
        public static Policy defaults() {
            return new Policy(100, 5.0, 1000, 100_000);
        }
    }

    /** 배치 하나가 끝날 때와 상태가 바뀔 때 알린다. 기록·화면은 이 알림으로 만든다(#104). */
    public interface Listener {
        default void batch(BulkBatchOutcome outcome, ReplicationState lag) {
        }

        default void state(State state, String reason) {
        }
    }

    /** 잠들 시간을 바꿔 끼울 수 있게 — 테스트가 실제로 기다리지 않게 한다. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final DbmsOperator operator;
    private final ConsoleCredential credential;
    private final BulkChangePlan plan;
    private final Policy policy;
    private final Listener listener;
    private final Sleeper sleeper;

    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    private final AtomicReference<Object> lastKey = new AtomicReference<>();
    private volatile boolean pauseRequested;
    private volatile boolean cancelRequested;
    private volatile long affectedRows;
    private volatile int batches;

    public BulkChangeRun(DbmsOperator operator, ConsoleCredential credential, BulkChangePlan plan,
                         Policy policy, Listener listener, Sleeper sleeper) {
        this.operator = operator;
        this.credential = credential;
        this.plan = plan;
        this.policy = policy;
        this.listener = listener;
        this.sleeper = sleeper;
    }

    public State state() {
        return state.get();
    }

    /** 마지막으로 적용을 마친 키 — 재개 지점이자 "어디까지 적용됐나"의 답이다. */
    public Object lastAppliedKey() {
        return lastKey.get();
    }

    public long affectedRows() {
        return affectedRows;
    }

    public int batches() {
        return batches;
    }

    /** 사람이 멈춘다. 실행 중인 배치는 끝까지 간다. */
    public void pause() {
        pauseRequested = true;
    }

    public void resume() {
        pauseRequested = false;
    }

    /** 사람이 취소한다. 커밋된 배치는 되돌리지 않는다. */
    public void cancel() {
        cancelRequested = true;
    }

    /**
     * 끝까지 돌린다. 호출한 스레드에서 돈다 — 스레드를 누가 쥐는지는 호출자가 정한다.
     *
     * @return 마지막 상태(DONE / CANCELLED / FAILED)
     */
    public State run() {
        try {
            while (true) {
                if (cancelRequested) {
                    return finish(State.CANCELLED, "사람이 취소했다");
                }
                if (batches >= policy.maxBatches()) {
                    return finish(State.FAILED, "배치 수 상한 " + policy.maxBatches() + "을 넘었다");
                }
                if (pauseRequested && !waitWhile(State.PAUSED_BY_USER, "사람이 멈췄다", () -> pauseRequested)) {
                    return state.get();
                }
                ReplicationState lag = replicationState();
                if (lagExceeded(lag)
                        && !waitWhile(State.PAUSED_LAG, lagReason(lag), () -> lagExceeded(replicationState()))) {
                    return state.get();
                }

                Object toKey = operator.nextBulkBoundary(credential, plan, lastKey.get());
                if (toKey == null) {
                    return finish(State.DONE, null);
                }
                BulkBatchOutcome outcome = operator.executeBulkBatch(credential, plan, lastKey.get(), toKey);
                lastKey.set(toKey);
                affectedRows += outcome.affectedRows();
                batches++;
                listener.batch(outcome, lag);

                sleeper.sleep(policy.pauseMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finish(State.FAILED, "실행 스레드가 중단됐다");
        } catch (OperatorException e) {
            log.error("대량 일괄 변경 배치 실패 table={} lastKey={}", plan.table(), lastKey.get(), e);
            return finish(State.FAILED, e.getMessage());
        }
    }

    /**
     * 조건이 풀릴 때까지 멈춘다. 멈춘 동안에도 취소는 받는다 — 복제가 영영 안 따라오면 사람이 끝낼 수 있어야 한다.
     *
     * @return 계속 진행해도 되면 true, 취소돼 끝났으면 false
     */
    private boolean waitWhile(State paused, String reason, java.util.function.BooleanSupplier stillBlocked)
            throws InterruptedException {
        if (state.getAndSet(paused) != paused) {
            listener.state(paused, reason);
        }
        while (stillBlocked.getAsBoolean()) {
            if (cancelRequested) {
                finish(State.CANCELLED, "멈춘 중에 취소했다");
                return false;
            }
            sleeper.sleep(policy.lagPollMillis());
        }
        state.set(State.RUNNING);
        listener.state(State.RUNNING, null);
        return true;
    }

    /**
     * 복제 상태를 읽다 실패하면 지연으로 보지 않는다 — 읽기 실패로 멈추면 복제가 없는 단독 대상에서도 진행이 막힌다.
     * 대신 "못 읽었다"는 사실이 {@link ReplicationState}에 그대로 담겨 배치 기록에 남는다.
     */
    private ReplicationState replicationState() {
        try {
            return operator.replicationState();
        } catch (RuntimeException e) {
            return new ReplicationState("UNKNOWN", null, ReplicationState.LagSource.UNAVAILABLE,
                    "복제 상태 조회 실패: " + e.getMessage());
        }
    }

    private boolean lagExceeded(ReplicationState lag) {
        return lag != null && lag.lagSource() == ReplicationState.LagSource.MEASURED
                && lag.lagSeconds() != null && lag.lagSeconds() > policy.lagThresholdSecond();
    }

    private String lagReason(ReplicationState lag) {
        return "복제 지연 " + lag.lagSeconds() + "초가 임계 " + policy.lagThresholdSecond() + "초를 넘었다";
    }

    private State finish(State end, String reason) {
        state.set(end);
        listener.state(end, reason);
        return end;
    }
}
