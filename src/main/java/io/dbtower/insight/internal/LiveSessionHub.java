package io.dbtower.insight.internal;

import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 실시간 세션 관제 허브 — 대상 하나를 보는 사람이 몇 명이든 대상 DB에는 한 번만 묻는다.
 *
 * <p><b>왜 브라우저 폴링이 아닌가.</b> 화면이 2초마다 {@code GET /sessions}를 부르면 대상 DB 조회 수가
 * "보는 사람 수 × 주기"로 는다. 장애가 나면 모두가 같은 대상을 열어 두므로, 관측이 가장 필요한 순간에
 * 관측 자체가 부하가 된다(A9 원칙 위반). 여기서는 대상별 채널 하나가 주기마다 한 번 조회하고 그 결과를
 * 구독자 전원에게 나눠 준다. 구독자가 0이 되면 조회도 멈춘다 — 아무도 안 보는 대상은 두드리지 않는다.
 *
 * <p><b>왜 ASH 샘플을 재사용하지 않는가.</b> ASH는 기본 꺼짐·PostgreSQL 전용이고, 개인정보 때문에 쿼리
 * 원문 대신 지문만 남긴다. 화면이 보여줘야 하는 것은 5기종 전부의 "지금 이 SQL"이라 원천이 다르다.
 * 둘 다 같은 {@code activeSessions()}를 부르므로 기종별 구현은 하나로 유지된다.
 *
 * <p><b>백프레셔.</b> 채널마다 fixedDelay로 돌기 때문에 느린 대상은 스스로 주기가 늘어난다(쌓이지 않는다).
 * 틱마다 수집 시간(collectMs)을 프레임에 실어, 화면이 "2초마다"가 아니라 "실제로 몇 초마다"였는지 보인다.
 *
 * <p><b>권한.</b> 팀 스코프는 구독 시점에 요청 주체로 확인한다(컨트롤러의 {@code findById}). 채널은 인가된
 * 사람끼리만 공유되고, 틱 스레드는 인증이 없어 전역으로 대상을 다시 찾는다 — 삭제되면 GONE으로 닫는다.
 * 권한 회수는 연결 수명({@code emitter-timeout-ms}) 안에 반영된다: 재연결이 곧 재인가다.
 */
@Component
public class LiveSessionHub {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionHub.class);

    /** 1초 미만은 받지 않는다 — 락은 초 단위로 생겼다 사라지고, 그보다 촘촘하면 관측이 부하가 된다. */
    static final long MIN_INTERVAL_MS = 1000;

    public static final String OK = "OK";
    public static final String ERROR = "ERROR";
    public static final String GONE = "GONE";

    /** 구독자 한 명 — SSE 연결을 모르게 해 두어 허브를 네트워크 없이 검증할 수 있다. */
    public interface Subscriber {
        void send(LiveFrame frame) throws IOException;

        void end();
    }

    /** 구독 해제 손잡이. 여러 번 불러도 한 번만 해제된다(onCompletion·onTimeout·onError가 겹쳐 온다). */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** 동시 구독 상한 초과 — 대상 조회가 아니라 앱 스레드·소켓을 지키는 상한이다. */
    public static class CapacityExceededException extends RuntimeException {
        public CapacityExceededException(int max) {
            super("실시간 구독이 상한(" + max + ")에 도달했습니다. 잠시 뒤 다시 시도하세요.");
        }
    }

    /**
     * @param seq       채널 안에서 단조 증가 — 화면이 끊김(결번)을 알아볼 수 있게
     * @param collectMs 대상 조회 왕복 시간. 주기보다 길어지면 실제 갱신 간격도 그만큼 늘어난다
     */
    public record LiveFrame(long instanceId, long seq, long atEpochMs, long intervalMs, double collectMs,
                            String status, String error, Summary summary, List<SessionInfo> sessions) {
    }

    /** 표를 다 읽지 않아도 보이는 요약 — 추이 그래프가 이것만 쌓는다. */
    public record Summary(int total, int blocked, int waiting, double longestMs) {
        static Summary of(List<SessionInfo> sessions) {
            int blocked = 0;
            int waiting = 0;
            double longest = 0;
            for (SessionInfo s : sessions) {
                if (s.blockedByPid() != null) {
                    blocked++;
                }
                if (s.waitEvent() != null && !s.waitEvent().isBlank()) {
                    waiting++;
                }
                longest = Math.max(longest, s.elapsedMs());
            }
            return new Summary(sessions.size(), blocked, waiting, longest);
        }
    }

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final ScheduledExecutorService executor;
    private final MeterRegistry meterRegistry;
    private final long intervalMs;
    private final int maxSessions;
    private final int maxSubscribers;

    private final Map<Long, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicInteger subscribers = new AtomicInteger();

    @Autowired
    public LiveSessionHub(RegistryService registryService,
                          DbmsOperatorFactory operatorFactory,
                          MeterRegistry meterRegistry,
                          @Value("${dbtower.live.interval-ms:2000}") long intervalMs,
                          @Value("${dbtower.live.max-sessions:50}") int maxSessions,
                          @Value("${dbtower.live.max-subscribers:200}") int maxSubscribers,
                          @Value("${dbtower.live.workers:2}") int workers) {
        this(registryService, operatorFactory, meterRegistry, intervalMs, maxSessions, maxSubscribers,
                Executors.newScheduledThreadPool(Math.max(1, workers), r -> {
                    // 스케줄러 공용 풀(dbtower-sched)을 쓰지 않는다 — 느린 대상 조회가 폴러 전체를 밀면 안 된다
                    Thread t = new Thread(r, "live-sessions");
                    t.setDaemon(true);
                    return t;
                }));
    }

    LiveSessionHub(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                   MeterRegistry meterRegistry, long intervalMs, int maxSessions, int maxSubscribers,
                   ScheduledExecutorService executor) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.meterRegistry = meterRegistry;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
        this.maxSessions = maxSessions;
        this.maxSubscribers = maxSubscribers;
        this.executor = executor;
        Gauge.builder("dbtower.live.subscribers", subscribers, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("dbtower.live.channels", channels, Map::size).register(meterRegistry);
    }

    /**
     * 구독. 호출자는 이미 {@code RegistryService.findById}로 스코프를 통과한 상태여야 한다.
     * 채널에 직전 프레임이 있으면 바로 한 장 보내 준다 — 두 번째 사람이 한 주기를 빈 화면으로 기다리지 않게.
     */
    public Subscription subscribe(long instanceId, Subscriber subscriber) {
        if (subscribers.incrementAndGet() > maxSubscribers) {
            subscribers.decrementAndGet();
            throw new CapacityExceededException(maxSubscribers);
        }
        Channel channel = channels.compute(instanceId, (id, existing) -> {
            Channel c = existing != null ? existing : new Channel(id);
            c.members.add(subscriber);
            if (c.future == null) {
                c.future = executor.scheduleWithFixedDelay(() -> tick(id), 0, intervalMs, TimeUnit.MILLISECONDS);
            }
            return c;
        });
        LiveFrame last = channel.last;
        if (last != null) {
            deliver(channel, subscriber, last);
        }
        return new Subscription() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    unsubscribe(instanceId, subscriber);
                }
            }
        };
    }

    private void unsubscribe(long instanceId, Subscriber subscriber) {
        channels.computeIfPresent(instanceId, (id, c) -> {
            if (c.members.remove(subscriber)) {
                subscribers.decrementAndGet();
            }
            if (c.members.isEmpty()) {
                // 아무도 안 보면 대상 조회를 멈춘다. 진행 중인 조회는 끊지 않는다(false) — 커넥션을 반쯤 쓴 채 버리지 않게
                if (c.future != null) {
                    c.future.cancel(false);
                }
                return null;
            }
            return c;
        });
    }

    /** 채널 한 틱 = 대상 조회 1회. 테스트가 스케줄러 없이 부를 수 있게 패키지 공개. */
    void tick(long instanceId) {
        Channel channel = channels.get(instanceId);
        if (channel == null || channel.members.isEmpty()) {
            return;
        }
        long seq = channel.seq.incrementAndGet();
        Optional<DatabaseInstance> instance = registryService.findOptional(instanceId);
        if (instance.isEmpty()) {
            broadcast(channel, frame(instanceId, seq, 0, GONE, "인스턴스가 삭제되었습니다.", List.of()));
            closeChannel(instanceId);
            return;
        }
        long start = System.nanoTime();
        LiveFrame frame;
        try {
            List<SessionInfo> sessions = operatorFactory.create(instance.get()).activeSessions(maxSessions);
            frame = frame(instanceId, seq, elapsedMs(start), OK, null, sessions);
        } catch (RuntimeException e) {
            // 실패도 프레임으로 보낸다 — 조용히 건너뛰면 화면은 "세션 0건"과 "못 쟀다"를 구분하지 못한다
            frame = frame(instanceId, seq, elapsedMs(start), ERROR, truncate(e.getMessage()), List.of());
            log.debug("실시간 세션 조회 실패 instance={} cause={}", instanceId, e.getMessage());
        }
        meterRegistry.counter("dbtower.live.polls", "status", frame.status()).increment();
        channel.last = frame;
        broadcast(channel, frame);
    }

    private LiveFrame frame(long instanceId, long seq, double collectMs, String status, String error,
                            List<SessionInfo> sessions) {
        return new LiveFrame(instanceId, seq, System.currentTimeMillis(), intervalMs, collectMs,
                status, error, Summary.of(sessions), sessions);
    }

    private void broadcast(Channel channel, LiveFrame frame) {
        for (Subscriber s : channel.members) {
            deliver(channel, s, frame);
        }
    }

    /** 보내기 실패 = 브라우저가 떠났다. 그 구독자만 떼고 나머지는 계속 받는다. */
    private void deliver(Channel channel, Subscriber subscriber, LiveFrame frame) {
        try {
            subscriber.send(frame);
        } catch (IOException | RuntimeException e) {
            unsubscribe(channel.instanceId, subscriber);
            subscriber.end();
        }
    }

    private void closeChannel(long instanceId) {
        Channel removed = channels.remove(instanceId);
        if (removed == null) {
            return;
        }
        if (removed.future != null) {
            removed.future.cancel(false);
        }
        for (Subscriber s : removed.members) {
            subscribers.decrementAndGet();
            s.end();
        }
        removed.members.clear();
    }

    int subscriberCount() {
        return subscribers.get();
    }

    boolean hasChannel(long instanceId) {
        return channels.containsKey(instanceId);
    }

    private static double elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000.0;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 300 ? s : s.substring(0, 300);
    }

    @PreDestroy
    void shutdown() {
        channels.keySet().forEach(this::closeChannel);
        executor.shutdownNow();
    }

    private static final class Channel {
        final long instanceId;
        final Set<Subscriber> members = ConcurrentHashMap.newKeySet();
        final AtomicLong seq = new AtomicLong();
        volatile LiveFrame last;
        ScheduledFuture<?> future;

        Channel(long instanceId) {
            this.instanceId = instanceId;
        }
    }
}
