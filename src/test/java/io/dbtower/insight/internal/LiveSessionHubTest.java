package io.dbtower.insight.internal;

import io.dbtower.analysis.QueryMasker;
import io.dbtower.insight.internal.LiveSessionHub.LiveFrame;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실시간 허브의 핵심 약속(VERIFICATION 140·144절) — 보는 사람이 몇 명이든, 앱 노드가 몇 대든 대상 조회는 주기당 1회, 아무도 안 보면 0회.
 *
 * <p>스케줄러는 가짜로 두고 틱을 직접 부른다. 조회권(ShedLock)은 "한 라운드에 한 번만 준다"는 가짜로 둔다 — 실제 락은 주기의 90% 동안
 * 쥐고 있어 한 주기 안에서는 두 번째 요청이 거절되는데, 그 성질만 남긴 것이다. 시간에 기대는 테스트는 CI에서 흔들린다.
 */
class LiveSessionHubTest {

    private static final long ID = 7L;

    private RegistryService registry;
    private DbmsOperatorFactory factory;
    private DbmsOperator operator;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private SimpleMeterRegistry meters;
    private RoundLock lock;
    private MemoryStore store;
    private LiveSessionHub hub;

    @BeforeEach
    void setUp() {
        registry = mock(RegistryService.class);
        factory = mock(DbmsOperatorFactory.class);
        operator = mock(DbmsOperator.class);
        DatabaseInstance instance = mock(DatabaseInstance.class);
        when(instance.getId()).thenReturn(ID);
        when(registry.findOptional(ID)).thenReturn(Optional.of(instance));
        when(factory.create(instance)).thenReturn(operator);
        when(operator.activeSessions(anyInt())).thenReturn(List.of(
                new SessionInfo(10, "app", "active", null, null, "select 1", 120),
                new SessionInfo(11, "app", "active", "Lock:transactionid", 10L,
                        "update customers set grade = 'VIP' where email = 'hong@x.com'", 3400)));

        executor = mock(ScheduledExecutorService.class);
        future = mock(ScheduledFuture.class);
        doReturn(future).when(executor).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        meters = new SimpleMeterRegistry();
        lock = new RoundLock();
        store = new MemoryStore();
        hub = node(meters, 3);
    }

    private LiveSessionHub node(SimpleMeterRegistry registryForNode, int maxSubscribers) {
        return new LiveSessionHub(registry, factory, registryForNode, lock, store, new QueryMasker(true, false),
                2000, 50, maxSubscribers, executor);
    }

    /** 한 주기 = 락 라운드 하나 + 틱 */
    private void round(LiveSessionHub... nodes) {
        lock.nextRound();
        for (LiveSessionHub n : nodes) {
            n.tick(ID);
        }
    }

    @Test
    void 구독자가_셋이어도_틱당_대상_조회는_한_번이다() {
        List<Recorder> viewers = List.of(new Recorder(), new Recorder(), new Recorder());
        viewers.forEach(v -> hub.subscribe(ID, v));

        for (int i = 0; i < 5; i++) {
            round(hub);
        }

        verify(executor, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(2000L), eq(TimeUnit.MILLISECONDS));
        verify(operator, times(5)).activeSessions(50);
        assertThat(meters.counter("dbtower.live.polls", "status", "OK").count()).isEqualTo(5.0);
        viewers.forEach(v -> assertThat(v.frames).extracting(LiveFrame::seq).containsExactly(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void 두_노드가_같은_대상을_보면_주기마다_한_노드만_묻고_다른_노드는_같은_프레임을_넘긴다() {
        SimpleMeterRegistry otherMeters = new SimpleMeterRegistry();
        LiveSessionHub other = node(otherMeters, 3);
        Recorder onA = new Recorder();
        Recorder onB = new Recorder();
        hub.subscribe(ID, onA);
        other.subscribe(ID, onB);

        for (int i = 0; i < 4; i++) {
            round(hub, other);
        }

        verify(operator, times(4)).activeSessions(50);
        assertThat(onA.frames).extracting(LiveFrame::seq).containsExactly(1L, 2L, 3L, 4L);
        assertThat(onB.frames).extracting(LiveFrame::seq).containsExactly(1L, 2L, 3L, 4L);
        assertThat(otherMeters.counter("dbtower.live.relays").count()).isEqualTo(4.0);
        assertThat(onB.frames.get(0).sessions()).isEqualTo(onA.frames.get(0).sessions());
    }

    @Test
    void 조회권을_쥔_노드가_올리기_전에_읽어_놓치면_잠시_뒤_한_번_더_읽어_받는다() {
        LiveSessionHub other = node(new SimpleMeterRegistry(), 3);
        Recorder onB = new Recorder();
        other.subscribe(ID, onB);
        lock.nextRound();
        lock.takenByOtherNode();                 // A가 조회권을 쥐었지만 아직 올리지 않은 순간
        other.tick(ID);

        assertThat(onB.frames).isEmpty();
        verify(executor).schedule(any(Runnable.class), eq(500L), eq(TimeUnit.MILLISECONDS));

        store.publish(new LiveFrame(ID, 0, 1L, 2000, 1.0, LiveSessionHub.OK, null,   // A가 이제 올림
                LiveSessionHub.Summary.of(List.of()), List.of()));
        other.retryRelay(ID);
        assertThat(onB.frames).extracting(LiveFrame::seq).containsExactly(1L);
        verify(operator, never()).activeSessions(anyInt());
    }

    @Test
    void 조회권을_쥔_노드가_사라지면_굳은_프레임은_넘기지_않고_락이_풀리면_이어받는다() {
        LiveSessionHub other = node(new SimpleMeterRegistry(), 3);
        Recorder onB = new Recorder();
        other.subscribe(ID, onB);
        hub.subscribe(ID, new Recorder());
        round(hub, other);                   // A가 묻고 올림, B는 넘김
        store.age = 10_000;                  // A가 죽어 프레임이 락 상한(6초)보다 오래됨
        lock.holdForever();                  // 죽은 A의 락이 아직 안 풀림
        other.tick(ID);

        assertThat(onB.frames).hasSize(1);   // 굳은 프레임을 또 넘기지 않는다
        lock.release();
        round(other);                        // 락 상한이 지나 B가 잡는다
        assertThat(onB.frames).extracting(LiveFrame::seq).containsExactly(1L, 2L);
        verify(operator, times(2)).activeSessions(50);
    }

    @Test
    void 락이_실패하면_조율_없이_직접_잰다() {
        lock.fail = true;
        Recorder viewer = new Recorder();
        hub.subscribe(ID, viewer);

        hub.tick(ID);
        hub.tick(ID);

        verify(operator, times(2)).activeSessions(50);
        assertThat(viewer.frames).extracting(LiveFrame::seq).containsExactly(1L, 2L);
        assertThat(store.published).isZero();
    }

    @Test
    void 세션_쿼리의_리터럴은_가려서_싣는다() {
        Recorder viewer = new Recorder();
        hub.subscribe(ID, viewer);
        round(hub);

        String query = viewer.frames.get(0).sessions().get(1).query();
        assertThat(query).doesNotContain("hong@x.com").doesNotContain("VIP").contains("email = ?");
        assertThat(store.frames.get(ID).sessions().get(1).query()).doesNotContain("hong@x.com");
    }

    @Test
    void 요약은_막힌_세션과_대기_중인_세션과_가장_오래_걸린_시간을_센다() {
        Recorder viewer = new Recorder();
        hub.subscribe(ID, viewer);
        round(hub);

        LiveSessionHub.Summary s = viewer.frames.get(0).summary();
        assertThat(s.total()).isEqualTo(2);
        assertThat(s.blocked()).isEqualTo(1);
        assertThat(s.waiting()).isEqualTo(1);
        assertThat(s.longestMs()).isEqualTo(3400);
    }

    @Test
    void 마지막_구독자가_떠나면_조회를_멈추고_채널을_지운다() {
        LiveSessionHub.Subscription a = hub.subscribe(ID, new Recorder());
        LiveSessionHub.Subscription b = hub.subscribe(ID, new Recorder());

        a.close();
        verify(future, never()).cancel(anyBoolean());
        b.close();
        b.close(); // onCompletion과 onTimeout이 겹쳐 와도 두 번 빼지 않는다

        verify(future).cancel(false);
        assertThat(hub.hasChannel(ID)).isFalse();
        assertThat(hub.subscriberCount()).isZero();
        round(hub); // 취소 직전에 이미 출발한 틱이 와도 대상에 닿지 않는다
        verify(operator, never()).activeSessions(anyInt());
    }

    @Test
    void 늦게_온_사람은_다음_틱을_기다리지_않고_직전_프레임을_바로_받는다() {
        hub.subscribe(ID, new Recorder());
        round(hub);

        Recorder late = new Recorder();
        hub.subscribe(ID, late);

        assertThat(late.frames).hasSize(1);
        verify(operator, times(1)).activeSessions(anyInt());
    }

    @Test
    void 보내기에_실패한_구독자만_떼고_나머지는_계속_받는다() {
        Recorder gone = new Recorder();
        gone.fail = true;
        Recorder stays = new Recorder();
        hub.subscribe(ID, gone);
        hub.subscribe(ID, stays);

        round(hub);
        round(hub);

        assertThat(gone.ended).isTrue();
        assertThat(stays.frames).hasSize(2);
        assertThat(hub.subscriberCount()).isEqualTo(1);
    }

    @Test
    void 대상_조회_실패는_건너뛰지_않고_ERROR_프레임으로_보낸다() {
        when(operator.activeSessions(anyInt())).thenThrow(new IllegalStateException("connection refused"));
        Recorder viewer = new Recorder();
        hub.subscribe(ID, viewer);

        round(hub);

        LiveFrame f = viewer.frames.get(0);
        assertThat(f.status()).isEqualTo(LiveSessionHub.ERROR);
        assertThat(f.error()).contains("connection refused");
        assertThat(f.sessions()).isEmpty();
    }

    @Test
    void 인스턴스가_지워지면_GONE을_보내고_연결을_닫는다() {
        when(registry.findOptional(ID)).thenReturn(Optional.empty());
        Recorder viewer = new Recorder();
        hub.subscribe(ID, viewer);

        round(hub);

        assertThat(viewer.frames).extracting(LiveFrame::status).containsExactly(LiveSessionHub.GONE);
        assertThat(viewer.ended).isTrue();
        assertThat(hub.hasChannel(ID)).isFalse();
        assertThat(hub.subscriberCount()).isZero();
    }

    @Test
    void 구독_상한을_넘으면_거절하고_자리를_차지하지_않는다() {
        hub.subscribe(ID, new Recorder());
        hub.subscribe(ID, new Recorder());
        hub.subscribe(8L, new Recorder());

        assertThatThrownBy(() -> hub.subscribe(9L, new Recorder()))
                .isInstanceOf(LiveSessionHub.CapacityExceededException.class);
        assertThat(hub.subscriberCount()).isEqualTo(3);
        assertThat(hub.hasChannel(9L)).isFalse();
    }

    @Test
    void 주기는_1초_아래로_내려가지_않는다() {
        LiveSessionHub fast = new LiveSessionHub(registry, factory, new SimpleMeterRegistry(), lock, store,
                new QueryMasker(true, false), 100, 50, 10, executor);
        fast.subscribe(ID, new Recorder());

        verify(executor).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1000L), eq(TimeUnit.MILLISECONDS));
    }

    /** 한 라운드(= 실제 락의 lockAtLeastFor 한 번)에 조회권을 한 번만 준다 */
    private static final class RoundLock implements LockProvider {
        private boolean taken;
        private boolean forever;
        boolean fail;

        void nextRound() {
            if (!forever) {
                taken = false;
            }
        }

        void takenByOtherNode() {
            taken = true;
        }

        void holdForever() {
            taken = true;
            forever = true;
        }

        void release() {
            forever = false;
            taken = false;
        }

        @Override
        public Optional<SimpleLock> lock(LockConfiguration configuration) {
            if (fail) {
                throw new IllegalStateException("meta db down");
            }
            if (taken) {
                return Optional.empty();
            }
            taken = true;
            return Optional.of(() -> { });
        }
    }

    private static final class MemoryStore implements LiveFrameStore {
        final Map<Long, LiveFrame> frames = new HashMap<>();
        long age;
        int published;

        @Override
        public long publish(LiveFrame frame) {
            long seq = frames.containsKey(frame.instanceId()) ? frames.get(frame.instanceId()).seq() + 1 : 1;
            frames.put(frame.instanceId(), frame.withSeq(seq));
            published++;
            age = 0;
            return seq;
        }

        @Override
        public Optional<Stored> latest(long instanceId) {
            return Optional.ofNullable(frames.get(instanceId)).map(f -> new Stored(f, age));
        }
    }

    private static final class Recorder implements LiveSessionHub.Subscriber {
        final List<LiveFrame> frames = new ArrayList<>();
        boolean fail;
        boolean ended;

        @Override
        public void send(LiveFrame frame) throws IOException {
            if (fail) {
                throw new IOException("broken pipe");
            }
            frames.add(frame);
        }

        @Override
        public void end() {
            ended = true;
        }
    }
}
