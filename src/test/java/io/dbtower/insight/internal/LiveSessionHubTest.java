package io.dbtower.insight.internal;

import io.dbtower.insight.internal.LiveSessionHub.LiveFrame;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * 실시간 허브의 핵심 약속(VERIFICATION 140절) — 보는 사람이 몇 명이든 대상 조회는 틱당 1회, 아무도 안 보면 0회.
 *
 * <p>스케줄러는 가짜로 두고 틱을 직접 부른다. 시간에 기대는 테스트는 CI에서 흔들리고, 여기서 확인할 것은
 * "몇 번 불렸나"이지 "언제 불렸나"가 아니다.
 */
class LiveSessionHubTest {

    private static final long ID = 7L;

    private RegistryService registry;
    private DbmsOperator operator;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private SimpleMeterRegistry meters;
    private LiveSessionHub hub;

    @BeforeEach
    void setUp() {
        registry = mock(RegistryService.class);
        DbmsOperatorFactory factory = mock(DbmsOperatorFactory.class);
        operator = mock(DbmsOperator.class);
        DatabaseInstance instance = mock(DatabaseInstance.class);
        when(registry.findOptional(ID)).thenReturn(Optional.of(instance));
        when(factory.create(instance)).thenReturn(operator);
        when(operator.activeSessions(anyInt())).thenReturn(List.of(
                new SessionInfo(10, "app", "active", null, null, "select 1", 120),
                new SessionInfo(11, "app", "active", "Lock:transactionid", 10L, "update t set v=1", 3400)));

        executor = mock(ScheduledExecutorService.class);
        future = mock(ScheduledFuture.class);
        doReturn(future).when(executor).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        meters = new SimpleMeterRegistry();
        hub = new LiveSessionHub(registry, factory, meters, 2000, 50, 3, executor);
    }

    @Test
    void 구독자가_셋이어도_틱당_대상_조회는_한_번이다() {
        List<Recorder> viewers = List.of(new Recorder(), new Recorder(), new Recorder());
        viewers.forEach(v -> hub.subscribe(ID, v));

        for (int i = 0; i < 5; i++) {
            hub.tick(ID);
        }

        verify(executor, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(2000L), eq(TimeUnit.MILLISECONDS));
        verify(operator, times(5)).activeSessions(50);
        assertThat(meters.counter("dbtower.live.polls", "status", "OK").count()).isEqualTo(5.0);
        viewers.forEach(v -> assertThat(v.frames).extracting(LiveFrame::seq).containsExactly(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void 요약은_막힌_세션과_대기_중인_세션과_가장_오래_걸린_시간을_센다() {
        Recorder viewer = new Recorder();
        hub.subscribe(ID, viewer);
        hub.tick(ID);

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
        hub.tick(ID); // 취소 직전에 이미 출발한 틱이 와도 대상에 닿지 않는다
        verify(operator, never()).activeSessions(anyInt());
    }

    @Test
    void 늦게_온_사람은_다음_틱을_기다리지_않고_직전_프레임을_바로_받는다() {
        hub.subscribe(ID, new Recorder());
        hub.tick(ID);

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

        hub.tick(ID);
        hub.tick(ID);

        assertThat(gone.ended).isTrue();
        assertThat(stays.frames).hasSize(2);
        assertThat(hub.subscriberCount()).isEqualTo(1);
    }

    @Test
    void 대상_조회_실패는_건너뛰지_않고_ERROR_프레임으로_보낸다() {
        when(operator.activeSessions(anyInt())).thenThrow(new IllegalStateException("connection refused"));
        Recorder viewer = new Recorder();
        hub.subscribe(ID, viewer);

        hub.tick(ID);

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

        hub.tick(ID);

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
        LiveSessionHub fast = new LiveSessionHub(registry, mock(DbmsOperatorFactory.class), new SimpleMeterRegistry(),
                100, 50, 10, executor);
        fast.subscribe(ID, new Recorder());

        verify(executor).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1000L), eq(TimeUnit.MILLISECONDS));
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
