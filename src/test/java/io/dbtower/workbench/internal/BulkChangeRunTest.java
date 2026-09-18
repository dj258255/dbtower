package io.dbtower.workbench.internal;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.BulkBatchOutcome;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.operator.model.ReplicationState.LagSource;
import io.dbtower.registry.ConsoleCredential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대량 일괄 변경의 진행 규칙 — 배치 경계, 복제 지연 멈춤·재개, 사람의 멈춤·취소.
 *
 * <p>대상 DB 없이 돈다. 오퍼레이터를 대역으로 두고 "어떤 순서로 무엇을 부르는가"와 "언제 멈추는가"만 본다.
 * 실제 DB에서 누락·중복이 없는지는 {@code BulkChangeBatchIT}가 docker compose 대상으로 확인한다.
 */
class BulkChangeRunTest {

    private static final ConsoleCredential CRED = new ConsoleCredential("w", "p");
    private static final BulkChangePlan PLAN =
            new BulkChangePlan("UPDATE t SET note = 'x'", "grade = 'VIP'", "t", "id", 1000, 30);

    /** 잠들지 않는다 — 쉬는 간격을 실제로 기다리면 테스트가 정책값만큼 느려진다. */
    private static final BulkChangeRun.Sleeper NO_SLEEP = millis -> {
    };

    private static final BulkChangeRun.Policy POLICY = new BulkChangeRun.Policy(100, 5.0, 10, 1000);

    private static ReplicationState lag(double seconds) {
        return new ReplicationState("REPLICA", seconds, LagSource.MEASURED, null);
    }

    private static ReplicationState standalone() {
        return new ReplicationState("STANDALONE", null, LagSource.NOT_APPLICABLE, "복제 구성 없음");
    }

    @Test
    @DisplayName("경계가 null이 될 때까지 배치를 이어 돌리고 마지막 키를 남긴다")
    void runsUntilBoundaryIsNull() {
        DbmsOperator op = mock(DbmsOperator.class);
        when(op.replicationState()).thenReturn(standalone());
        when(op.nextBulkBoundary(eq(CRED), eq(PLAN), any())).thenReturn(1000L, 2000L, 2500L, null);
        when(op.executeBulkBatch(eq(CRED), eq(PLAN), any(), any()))
                .thenAnswer(i -> new BulkBatchOutcome(i.getArgument(2), i.getArgument(3), 1000, 12));

        List<BulkBatchOutcome> seen = new ArrayList<>();
        BulkChangeRun run = new BulkChangeRun(op, CRED, PLAN, POLICY,
                new BulkChangeRun.Listener() {
                    @Override
                    public void batch(BulkBatchOutcome outcome, ReplicationState l) {
                        seen.add(outcome);
                    }
                }, NO_SLEEP);

        assertThat(run.run()).isEqualTo(BulkChangeRun.State.DONE);
        assertThat(run.batches()).isEqualTo(3);
        assertThat(run.affectedRows()).isEqualTo(3000);
        assertThat(run.lastAppliedKey()).isEqualTo(2500L);
        // 첫 배치는 하한이 없다 — 키 공간의 처음부터 연다
        assertThat(seen.get(0).fromKey()).isNull();
        assertThat(seen.get(1).fromKey()).isEqualTo(1000L);
        assertThat(seen.get(2).fromKey()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("실측 복제 지연이 임계를 넘으면 멈추고, 내려오면 스스로 재개한다")
    void pausesOnMeasuredLagAndResumes() {
        DbmsOperator op = mock(DbmsOperator.class);
        AtomicInteger reads = new AtomicInteger();
        // 처음 세 번은 밀려 있고 그 뒤로 따라잡는다
        when(op.replicationState()).thenAnswer(i -> reads.incrementAndGet() <= 3 ? lag(12.0) : lag(0.4));
        when(op.nextBulkBoundary(eq(CRED), eq(PLAN), any())).thenReturn(100L, (Object) null);
        when(op.executeBulkBatch(eq(CRED), eq(PLAN), any(), any()))
                .thenReturn(new BulkBatchOutcome(null, 100L, 100, 5));

        List<String> states = new ArrayList<>();
        BulkChangeRun run = new BulkChangeRun(op, CRED, PLAN, POLICY, new BulkChangeRun.Listener() {
            @Override
            public void state(BulkChangeRun.State s, String reason) {
                states.add(s + (reason == null ? "" : "(" + reason + ")"));
            }
        }, NO_SLEEP);

        assertThat(run.run()).isEqualTo(BulkChangeRun.State.DONE);
        assertThat(states).anyMatch(s -> s.startsWith("PAUSED_LAG(복제 지연 12.0초"));
        assertThat(states).contains("RUNNING");
        assertThat(states).endsWith("DONE");
        assertThat(run.batches()).isEqualTo(1);
    }

    @Test
    @DisplayName("지연을 못 재는 상태는 멈춤 사유가 아니다 — '못 잰다'를 '지연 0'으로도 '지연 큼'으로도 읽지 않는다")
    void unmeasuredLagDoesNotPause() {
        for (LagSource source : List.of(LagSource.NOT_APPLICABLE, LagSource.UNSUPPORTED, LagSource.UNAVAILABLE)) {
            DbmsOperator op = mock(DbmsOperator.class);
            when(op.replicationState()).thenReturn(new ReplicationState("R", null, source, "사유"));
            when(op.nextBulkBoundary(eq(CRED), eq(PLAN), any())).thenReturn(10L, (Object) null);
            when(op.executeBulkBatch(eq(CRED), eq(PLAN), any(), any()))
                    .thenReturn(new BulkBatchOutcome(null, 10L, 10, 1));

            List<BulkChangeRun.State> states = new ArrayList<>();
            BulkChangeRun run = new BulkChangeRun(op, CRED, PLAN, POLICY, new BulkChangeRun.Listener() {
                @Override
                public void state(BulkChangeRun.State s, String reason) {
                    states.add(s);
                }
            }, NO_SLEEP);

            assertThat(run.run()).as("%s", source).isEqualTo(BulkChangeRun.State.DONE);
            assertThat(states).as("%s", source).doesNotContain(BulkChangeRun.State.PAUSED_LAG);
        }
    }

    @Test
    @DisplayName("복제 상태 조회가 실패해도 진행을 막지 않고 그 사실을 배치 기록에 넘긴다")
    void replicationReadFailureDoesNotBlock() {
        DbmsOperator op = mock(DbmsOperator.class);
        when(op.replicationState()).thenThrow(new OperatorException("복제 조회 권한 없음"));
        when(op.nextBulkBoundary(eq(CRED), eq(PLAN), any())).thenReturn(10L, (Object) null);
        when(op.executeBulkBatch(eq(CRED), eq(PLAN), any(), any()))
                .thenReturn(new BulkBatchOutcome(null, 10L, 10, 1));

        AtomicReference<ReplicationState> recorded = new AtomicReference<>();
        BulkChangeRun run = new BulkChangeRun(op, CRED, PLAN, POLICY, new BulkChangeRun.Listener() {
            @Override
            public void batch(BulkBatchOutcome outcome, ReplicationState l) {
                recorded.set(l);
            }
        }, NO_SLEEP);

        assertThat(run.run()).isEqualTo(BulkChangeRun.State.DONE);
        assertThat(recorded.get().lagSource()).isEqualTo(LagSource.UNAVAILABLE);
        assertThat(recorded.get().detail()).contains("복제 조회 권한 없음");
    }

    @Test
    @DisplayName("취소하면 다음 배치를 시작하지 않고, 이미 커밋한 배치는 되돌리지 않는다")
    void cancelStopsBeforeNextBatch() {
        DbmsOperator op = mock(DbmsOperator.class);
        when(op.replicationState()).thenReturn(standalone());
        when(op.nextBulkBoundary(eq(CRED), eq(PLAN), any())).thenReturn(100L, 200L, 300L, null);

        AtomicReference<BulkChangeRun> self = new AtomicReference<>();
        when(op.executeBulkBatch(eq(CRED), eq(PLAN), any(), any())).thenAnswer(i -> {
            self.get().cancel();   // 첫 배치를 마치자마자 사람이 취소한다
            return new BulkBatchOutcome(i.getArgument(2), i.getArgument(3), 100, 3);
        });

        BulkChangeRun run = new BulkChangeRun(op, CRED, PLAN, POLICY, new BulkChangeRun.Listener() {
        }, NO_SLEEP);
        self.set(run);

        assertThat(run.run()).isEqualTo(BulkChangeRun.State.CANCELLED);
        assertThat(run.batches()).isEqualTo(1);
        assertThat(run.lastAppliedKey()).isEqualTo(100L);   // 어디까지 적용됐나가 남는다
        verify(op, never()).revertChange(any(), any());
    }

    @Test
    @DisplayName("멈춰 있는 동안에도 취소를 받는다 — 복제가 영영 안 따라와도 사람이 끝낼 수 있다")
    void cancelWhilePaused() {
        DbmsOperator op = mock(DbmsOperator.class);
        when(op.replicationState()).thenReturn(lag(99.0));   // 계속 밀려 있다
        when(op.nextBulkBoundary(eq(CRED), eq(PLAN), any())).thenReturn(100L);

        AtomicReference<BulkChangeRun> self = new AtomicReference<>();
        AtomicInteger sleeps = new AtomicInteger();
        BulkChangeRun run = new BulkChangeRun(op, CRED, PLAN, POLICY, new BulkChangeRun.Listener() {
        }, millis -> {
            if (sleeps.incrementAndGet() >= 3) {
                self.get().cancel();
            }
        });
        self.set(run);

        assertThat(run.run()).isEqualTo(BulkChangeRun.State.CANCELLED);
        assertThat(run.batches()).isZero();
        verify(op, never()).executeBulkBatch(any(), any(), any(), any());
    }

    @Test
    @DisplayName("사람이 멈추면 다음 배치를 시작하지 않고, 재개하면 이어서 돈다")
    void pauseByUserThenResume() {
        DbmsOperator op = mock(DbmsOperator.class);
        when(op.replicationState()).thenReturn(standalone());
        when(op.nextBulkBoundary(eq(CRED), eq(PLAN), any())).thenReturn(100L, 200L, null);

        AtomicReference<BulkChangeRun> self = new AtomicReference<>();
        AtomicInteger sleeps = new AtomicInteger();
        BulkChangeRun run = new BulkChangeRun(op, CRED, PLAN, POLICY, new BulkChangeRun.Listener() {
        }, millis -> {
            // 첫 배치 뒤 쉬는 사이에 멈추고, 멈춘 채 두 번 기다린 뒤 재개한다
            int n = sleeps.incrementAndGet();
            if (n == 1) {
                self.get().pause();
            } else if (n == 3) {
                self.get().resume();
            }
        });
        self.set(run);
        when(op.executeBulkBatch(eq(CRED), eq(PLAN), any(), any()))
                .thenAnswer(i -> new BulkBatchOutcome(i.getArgument(2), i.getArgument(3), 100, 2));

        assertThat(run.run()).isEqualTo(BulkChangeRun.State.DONE);
        assertThat(run.batches()).isEqualTo(2);
        assertThat(run.lastAppliedKey()).isEqualTo(200L);
    }

    @Test
    @DisplayName("배치가 실패하면 FAILED로 멈추고 다음 배치를 시도하지 않는다")
    void batchFailureStops() {
        DbmsOperator op = mock(DbmsOperator.class);
        when(op.replicationState()).thenReturn(standalone());
        when(op.nextBulkBoundary(eq(CRED), eq(PLAN), any())).thenReturn(100L, 200L, null);
        when(op.executeBulkBatch(eq(CRED), eq(PLAN), any(), any()))
                .thenThrow(new OperatorException("배치가 목표 행 수를 넘겨 커밋하지 않았다(목표 1000, 영향 4000)"));

        AtomicReference<String> reason = new AtomicReference<>();
        BulkChangeRun run = new BulkChangeRun(op, CRED, PLAN, POLICY, new BulkChangeRun.Listener() {
            @Override
            public void state(BulkChangeRun.State s, String r) {
                if (s == BulkChangeRun.State.FAILED) {
                    reason.set(r);
                }
            }
        }, NO_SLEEP);

        assertThat(run.run()).isEqualTo(BulkChangeRun.State.FAILED);
        assertThat(run.batches()).isZero();
        assertThat(reason.get()).contains("목표 행 수를 넘겨");
    }

    @Test
    @DisplayName("경계가 전진하지 않아도 배치 수 상한에서 멈춘다")
    void maxBatchesGuard() {
        DbmsOperator op = mock(DbmsOperator.class);
        when(op.replicationState()).thenReturn(standalone());
        when(op.nextBulkBoundary(eq(CRED), eq(PLAN), any())).thenReturn(1L);   // 늘 같은 키
        when(op.executeBulkBatch(eq(CRED), eq(PLAN), any(), any()))
                .thenReturn(new BulkBatchOutcome(1L, 1L, 0, 1));

        BulkChangeRun.Policy small = new BulkChangeRun.Policy(0, 5.0, 0, 5);
        BulkChangeRun run = new BulkChangeRun(op, CRED, PLAN, small, new BulkChangeRun.Listener() {
        }, NO_SLEEP);

        assertThat(run.run()).isEqualTo(BulkChangeRun.State.FAILED);
        assertThat(run.batches()).isEqualTo(5);
    }

    @Test
    @DisplayName("조건은 다시 쓰지 않고 키 범위만 덧붙인다")
    void whereKeepsApprovedCondition() {
        assertThat(PLAN.whereFor(false)).isEqualTo("(grade = 'VIP') AND id <= ?");
        assertThat(PLAN.whereFor(true)).isEqualTo("(grade = 'VIP') AND id > ? AND id <= ?");

        BulkChangePlan noCondition = new BulkChangePlan("DELETE FROM t", "", "t", "id", 500, 30);
        assertThat(noCondition.whereFor(false)).isEqualTo("id <= ?");
        assertThat(noCondition.whereFor(true)).isEqualTo("id > ? AND id <= ?");
    }
}
