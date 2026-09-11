package io.dbtower.insight.internal;

import io.dbtower.analysis.QueryMasker;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
 * 실시간 세션 관제 허브 — 대상 하나를 보는 사람이 몇 명이든, 앱 노드가 몇 대든 대상 DB에는 한 번만 묻는다.
 *
 * <p><b>왜 브라우저 폴링이 아닌가.</b> 화면이 2초마다 {@code GET /sessions}를 부르면 대상 DB 조회 수가
 * "보는 사람 수 × 주기"로 는다. 장애가 나면 모두가 같은 대상을 열어 두므로, 관측이 가장 필요한 순간에
 * 관측 자체가 부하가 된다(A9 원칙 위반). 여기서는 대상별 채널 하나가 주기마다 한 번 조회하고 그 결과를
 * 구독자 전원에게 나눠 준다. 구독자가 0이 되면 조회도 멈춘다 — 아무도 안 보는 대상은 두드리지 않는다.
 *
 * <p><b>노드가 여럿일 때(144절).</b> 140절의 허브는 노드마다 따로 조회해 대상 조회가 노드 수만큼 늘었다. 이제 틱마다 대상별
 * 조회권(ShedLock, 폴러들과 같은 메타 DB 락)을 먼저 잡는다. 잡은 노드만 대상에 묻고 프레임을 {@link LiveFrameStore}에 올리며,
 * 못 잡은 노드는 올라온 프레임을 읽어 자기 구독자에게 넘긴다. 조회권은 주기의 90%만큼 쥐고 있어 한 주기에 두 노드가 묻지 못한다.
 * 조회권을 쥔 노드가 죽으면 락 상한(주기의 3배)이 지나 다른 노드가 이어받는다. 락이나 저장소가 실패하면 조율 없이 이 노드가 직접 잰다
 * — 조율 실패가 관제 공백이 되면 안 되고, 그때 늘어나는 조회는 조율 전(노드 수만큼)과 같다.
 *
 * <p><b>왜 ASH 샘플을 재사용하지 않는가.</b> ASH는 기본 꺼짐·PostgreSQL 전용이고, 개인정보 때문에 쿼리
 * 원문 대신 지문만 남긴다. 화면이 보여줘야 하는 것은 5기종 전부의 "지금 이 SQL"이라 원천이 다르다.
 * 둘 다 같은 {@code activeSessions()}를 부르므로 기종별 구현은 하나로 유지된다.
 *
 * <p><b>쿼리 리터럴.</b> 세션 쿼리는 실행 중인 원문이라 {@code WHERE email = '...'} 같은 실값이 들어 있다. 프레임은 노드 사이에서
 * 메타 DB에도 머물므로, 웹훅·AI 도구 인자와 같은 규칙({@code dbtower.masking.enabled})으로 리터럴을 가린 뒤 싣는다(144절).
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
    static final String LOCK_PREFIX = "live-sessions-";

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
     * @param seq       대상별 단조 증가(노드가 여럿이면 저장소가 매긴다) — 화면이 끊김(결번)을 알아볼 수 있게
     * @param collectMs 대상 조회 왕복 시간. 주기보다 길어지면 실제 갱신 간격도 그만큼 늘어난다
     */
    public record LiveFrame(long instanceId, long seq, long atEpochMs, long intervalMs, double collectMs,
                            String status, String error, Summary summary, List<SessionInfo> sessions) {
        LiveFrame withSeq(long newSeq) {
            return new LiveFrame(instanceId, newSeq, atEpochMs, intervalMs, collectMs, status, error, summary, sessions);
        }
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
    private final LockProvider lockProvider;
    private final LiveFrameStore store;
    private final QueryMasker queryMasker;
    private final long intervalMs;
    private final Duration lockAtMost;
    private final Duration lockAtLeast;
    private final int maxSessions;
    private final int maxSubscribers;

    private final Map<Long, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicInteger subscribers = new AtomicInteger();

    @Autowired
    public LiveSessionHub(RegistryService registryService,
                          DbmsOperatorFactory operatorFactory,
                          MeterRegistry meterRegistry,
                          LockProvider lockProvider,
                          LiveFrameStore store,
                          QueryMasker queryMasker,
                          @Value("${dbtower.live.interval-ms:2000}") long intervalMs,
                          @Value("${dbtower.live.max-sessions:50}") int maxSessions,
                          @Value("${dbtower.live.max-subscribers:200}") int maxSubscribers,
                          @Value("${dbtower.live.workers:2}") int workers) {
        this(registryService, operatorFactory, meterRegistry, lockProvider, store, queryMasker,
                intervalMs, maxSessions, maxSubscribers,
                Executors.newScheduledThreadPool(Math.max(1, workers), r -> {
                    // 스케줄러 공용 풀(dbtower-sched)을 쓰지 않는다 — 느린 대상 조회가 폴러 전체를 밀면 안 된다
                    Thread t = new Thread(r, "live-sessions");
                    t.setDaemon(true);
                    return t;
                }));
    }

    LiveSessionHub(RegistryService registryService, DbmsOperatorFactory operatorFactory, MeterRegistry meterRegistry,
                   LockProvider lockProvider, LiveFrameStore store, QueryMasker queryMasker,
                   long intervalMs, int maxSessions, int maxSubscribers, ScheduledExecutorService executor) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.meterRegistry = meterRegistry;
        this.lockProvider = lockProvider;
        this.store = store;
        this.queryMasker = queryMasker;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
        // 조회권을 주기의 90% 동안 쥔다(ASH 샘플러와 같은 비율) — 노드마다 틱 위상이 달라도 한 주기에 두 번 묻지 못하게.
        // 상한 3주기는 쥔 노드가 죽었을 때 넘어가는 시간이다
        this.lockAtLeast = Duration.ofMillis(this.intervalMs * 9 / 10);
        this.lockAtMost = Duration.ofMillis(this.intervalMs * 3);
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

    /** 채널 한 틱 — 조회권을 잡으면 대상 조회 1회, 못 잡으면 다른 노드가 올린 프레임을 넘긴다. 테스트가 스케줄러 없이 부를 수 있게 패키지 공개. */
    void tick(long instanceId) {
        Channel channel = channels.get(instanceId);
        if (channel == null || channel.members.isEmpty()) {
            return;
        }
        Optional<DatabaseInstance> instance = registryService.findOptional(instanceId);
        if (instance.isEmpty()) {
            broadcast(channel, frame(instanceId, 0, GONE, "인스턴스가 삭제되었습니다.", List.of())
                    .withSeq(channel.lastSeq.get() + 1));
            closeChannel(instanceId);
            return;
        }
        Optional<SimpleLock> lease;
        try {
            lease = lockProvider.lock(new LockConfiguration(Instant.now(), LOCK_PREFIX + instanceId, lockAtMost, lockAtLeast));
        } catch (RuntimeException e) {
            log.debug("실시간 조회권 획득 실패 — 이 노드가 직접 잰다 instance={} cause={}", instanceId, e.getMessage());
            pollAndDeliver(channel, instance.get(), false);
            return;
        }
        if (lease.isEmpty()) {
            if (!relay(channel)) {
                // 두 노드의 틱이 거의 같은 순간이면 조회권을 쥔 노드가 올리기 전에 읽어 그 프레임을 놓친다(144절 1차 실측: 30초 동안
                // 노드마다 3~4장 결번). 주기의 1/4 뒤에 한 번만 다시 읽는다 — 늦게라도 받는 편이 결번보다 낫고, 추가 비용은 메타 DB 읽기 한 번이다
                try {
                    executor.schedule(() -> retryRelay(instanceId), intervalMs / 4, TimeUnit.MILLISECONDS);
                } catch (RuntimeException e) {
                    log.debug("실시간 프레임 재시도 예약 실패(종료 중) instance={}", instanceId);
                }
            }
            return;
        }
        try {
            pollAndDeliver(channel, instance.get(), true);
        } finally {
            try {
                lease.get().unlock();
            } catch (RuntimeException e) {
                // 못 풀어도 상한(3주기)에 풀린다. 틱 스레드를 여기서 죽이지 않는다
                log.debug("실시간 조회권 해제 실패 instance={} cause={}", instanceId, e.getMessage());
            }
        }
    }

    private void pollAndDeliver(Channel channel, DatabaseInstance instance, boolean shared) {
        long start = System.nanoTime();
        LiveFrame frame;
        try {
            List<SessionInfo> sessions = operatorFactory.create(instance).activeSessions(maxSessions).stream()
                    .map(this::mask)
                    .toList();
            frame = frame(instance.getId(), elapsedMs(start), OK, null, sessions);
        } catch (RuntimeException e) {
            // 실패도 프레임으로 보낸다 — 조용히 건너뛰면 화면은 "세션 0건"과 "못 쟀다"를 구분하지 못한다.
            // 원문은 싣지 않는다: 드라이버 문장(호스트·스키마)이 구독자 전원과 노드 공유 테이블(live_frame)로 퍼진다(148절 감사, CWE-209)
            frame = frame(instance.getId(), elapsedMs(start), ERROR, "대상 DB 세션을 조회하지 못했습니다(" + e.getClass().getSimpleName() + ")", List.of());
            log.debug("실시간 세션 조회 실패 instance={} cause={}", instance.getId(), e.getMessage());
        }
        meterRegistry.counter("dbtower.live.polls", "status", frame.status()).increment();
        long seq = channel.lastSeq.get() + 1;
        if (shared) {
            try {
                seq = store.publish(frame);
            } catch (RuntimeException e) {
                // 올리지 못하면 다른 노드는 이 프레임을 못 받는다(그 노드는 다음 틱에 스스로 조회권을 잡는다). 내 구독자는 받는다
                log.debug("실시간 프레임 올리기 실패 instance={} cause={}", instance.getId(), e.getMessage());
            }
        }
        // 올리기에 실패한 틱은 로컬 번호(last+1)로 보낸다. 저장소가 돌아와 그보다 작은 번호를 주면 화면에 번호가 거꾸로 가
        // 결번 판정이 흔들린다(148절 감사) — 이 노드가 내보내는 번호는 줄지 않게 한다
        publishLocally(channel, frame.withSeq(Math.max(seq, channel.lastSeq.get() + 1)));
    }

    /**
     * 다른 노드가 올린 프레임을 넘긴다. 이미 넘긴 것(seq 이하)과 쥔 노드가 죽어 굳은 것(락 상한보다 오래됨)은 넘기지 않는다.
     *
     * @return 새 프레임을 넘겼으면 true
     */
    private boolean relay(Channel channel) {
        Optional<LiveFrameStore.Stored> stored;
        try {
            stored = store.latest(channel.instanceId);
        } catch (RuntimeException e) {
            log.debug("실시간 프레임 읽기 실패 instance={} cause={}", channel.instanceId, e.getMessage());
            return false;
        }
        if (stored.isEmpty()) {
            return false;
        }
        LiveFrameStore.Stored s = stored.get();
        if (s.ageMs() > lockAtMost.toMillis() || s.frame().seq() <= channel.lastSeq.get()) {
            return false;
        }
        meterRegistry.counter("dbtower.live.relays").increment();
        publishLocally(channel, s.frame());
        return true;
    }

    /** 놓친 프레임 한 번 더 읽기 — 그 사이 구독자가 다 떠났으면 읽지 않는다. 테스트가 부를 수 있게 패키지 공개. */
    void retryRelay(long instanceId) {
        Channel channel = channels.get(instanceId);
        if (channel == null || channel.members.isEmpty()) {
            return;
        }
        if (relay(channel)) {
            meterRegistry.counter("dbtower.live.relay.retries", "outcome", "delivered").increment();
        } else {
            meterRegistry.counter("dbtower.live.relay.retries", "outcome", "nothing").increment();
        }
    }

    private void publishLocally(Channel channel, LiveFrame frame) {
        channel.lastSeq.accumulateAndGet(frame.seq(), Math::max);
        channel.last = frame;
        broadcast(channel, frame);
    }

    private SessionInfo mask(SessionInfo s) {
        return new SessionInfo(s.pid(), s.user(), s.state(), s.waitEvent(), s.blockedByPid(),
                queryMasker.apply(s.query()), s.elapsedMs());
    }

    private LiveFrame frame(long instanceId, double collectMs, String status, String error, List<SessionInfo> sessions) {
        return new LiveFrame(instanceId, 0, System.currentTimeMillis(), intervalMs, collectMs,
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


    @PreDestroy
    void shutdown() {
        channels.keySet().forEach(this::closeChannel);
        executor.shutdownNow();
    }

    private static final class Channel {
        final long instanceId;
        final Set<Subscriber> members = ConcurrentHashMap.newKeySet();
        final AtomicLong lastSeq = new AtomicLong();
        volatile LiveFrame last;
        ScheduledFuture<?> future;

        Channel(long instanceId) {
            this.instanceId = instanceId;
        }
    }
}
