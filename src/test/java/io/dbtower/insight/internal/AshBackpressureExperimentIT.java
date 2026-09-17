package io.dbtower.insight.internal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E4 — 활성 세션 샘플러가 느려질 때 어떻게 밀릴 것인가. 세 정책의 대가를 같은 대상에서 잰다.
 *
 * <pre>
 * DELAY    : 한 틱이 끝난 뒤 1초 쉬고 다음 틱 (제품 AshSamplerJob의 fixedDelay + 완료 대기). 느리면 틱 간격이 늘어난다
 * QUEUE    : 1초 간격 계획을 지키려고 밀린 틱을 쌓아 순서대로 따라잡는다. 대상 조회는 한 번에 하나
 * PARALLEL : 직전 틱을 기다리지 않고 매초 새 조회를 쏜다. 대상 조회가 겹친다
 * </pre>
 *
 * <p>대상 PostgreSQL에서 4초마다 약 1.5초짜리 락 막힘을 만든다. 샘플러 조회는 그 순간 막힌 세션 수를 센다.
 * 느린 대상은 조회 앞에 지연을 주입해 흉내 낸다(실제 과부하는 조회가 몰릴수록 더 느려지지만 이 주입은 부하와 무관하다 — 한계).
 *
 * <p>게이트: {@code DBTOWER_EXPERIMENT=1 ./gradlew cleanTest test --tests '*AshBackpressureExperimentIT'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_EXPERIMENT", matches = "1")
class AshBackpressureExperimentIT {

    private static final String URL = "jdbc:postgresql://127.0.0.1:15432/sample";
    private static final String USER = "postgres";
    private static final String PASSWORD = "dbtower1234";
    private static final long WINDOW_MS = 40_000;
    private static final long INTERVAL_MS = 1_000;
    private static final long EPISODE_EVERY_MS = 4_000;
    private static final long EPISODE_HOLD_MS = 1_500;
    private static final long[] DELAYS_MS = {0, 2_000};
    private static final Path DOC = Path.of("docs", "experiments", "ash-backpressure.md");
    private static final Path CSV = Path.of("docs", "experiments", "ash-backpressure.csv");

    enum Policy { DELAY, QUEUE, PARALLEL }

    record Sample(long intendedMs, long snapshotMs, int blocked) {
    }

    record Episode(long blockStartMs, long blockEndMs) {
    }

    record Result(Policy policy, long delayMs, int samples, int samplesInWindow, int episodes, int captured,
                  int capturedAtRightTime, long skewP50Ms, long skewMaxMs, int maxConcurrent, long lastSnapshotAfterWindowMs) {
    }

    private static Connection open(String app) throws SQLException {
        Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
        try (Statement st = c.createStatement()) {
            st.execute("SET application_name = '" + app + "'");
        }
        return c;
    }

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection c = open("e4-setup"); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS exp_bp");
            st.execute("CREATE TABLE exp_bp (id INT PRIMARY KEY, v INT)");
            st.execute("INSERT INTO exp_bp VALUES (1, 0)");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        try (Connection c = open("e4-setup"); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS exp_bp");
        }
    }

    @Test
    void 샘플러가_느려질_때_세_정책의_대가를_잰다() throws Exception {
        String startedAt = OffsetDateTime.now().toString();
        List<Result> results = new ArrayList<>();
        StringBuilder csv = new StringBuilder("policy,delay_ms,intended_ms,snapshot_ms,blocked\n");
        for (long delay : DELAYS_MS) {
            for (Policy policy : Policy.values()) {
                results.add(runOnce(policy, delay, csv));
            }
        }
        Files.writeString(CSV, csv, StandardCharsets.UTF_8);
        Files.writeString(DOC, document(results, startedAt), StandardCharsets.UTF_8);
        System.out.println("[E4] 결과 기록: " + DOC.toAbsolutePath());
        assertTrue(results.stream().allMatch(r -> r.episodes() > 0), "막힘 사건이 만들어지지 않았다 — 측정 조건이 서지 않았다");
        assertTrue(results.stream().filter(r -> r.delayMs() == 0).allMatch(r -> r.captured() > 0),
                "지연이 없을 때도 한 사건도 못 잡았다 — 샘플러 조회가 막힘을 보지 못하는 설정이다");
    }

    private Result runOnce(Policy policy, long delayMs, StringBuilder csv) throws Exception {
        List<Episode> episodes = Collections.synchronizedList(new ArrayList<>());
        List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean stop = new AtomicBoolean();
        long t0 = System.currentTimeMillis();

        Thread generator = new Thread(() -> generateEpisodes(t0, episodes, stop), "e4-episodes");
        generator.start();

        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        switch (policy) {
            case DELAY -> {
                try (Connection c = open("e4-sampler")) {
                    while (System.currentTimeMillis() - t0 < WINDOW_MS) {
                        long intended = System.currentTimeMillis();
                        samples.add(sampleOnce(c, intended, delayMs, concurrent, maxConcurrent));
                        Thread.sleep(INTERVAL_MS);
                    }
                }
            }
            case QUEUE -> {
                try (Connection c = open("e4-sampler")) {
                    for (long k = 0; ; k++) {
                        long intended = t0 + k * INTERVAL_MS;
                        if (intended - t0 >= WINDOW_MS) {
                            break;
                        }
                        long wait = intended - System.currentTimeMillis();
                        if (wait > 0) {
                            Thread.sleep(wait);
                        }
                        samples.add(sampleOnce(c, intended, delayMs, concurrent, maxConcurrent));
                    }
                }
            }
            case PARALLEL -> {
                ExecutorService pool = Executors.newCachedThreadPool();
                for (long k = 0; ; k++) {
                    long intended = t0 + k * INTERVAL_MS;
                    if (intended - t0 >= WINDOW_MS) {
                        break;
                    }
                    long wait = intended - System.currentTimeMillis();
                    if (wait > 0) {
                        Thread.sleep(wait);
                    }
                    pool.submit(() -> {
                        try (Connection c = open("e4-sampler")) {
                            samples.add(sampleOnce(c, intended, delayMs, concurrent, maxConcurrent));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        return null;
                    });
                }
                pool.shutdown();
                pool.awaitTermination(60, TimeUnit.SECONDS);
            }
        }
        stop.set(true);
        generator.join(10_000);

        List<Sample> all = new ArrayList<>(samples);
        all.sort((a, b) -> Long.compare(a.intendedMs(), b.intendedMs()));
        for (Sample s : all) {
            csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%d%n", policy, delayMs, s.intendedMs() - t0, s.snapshotMs() - t0, s.blocked()));
        }
        List<Episode> eps = new ArrayList<>(episodes);
        int captured = 0;
        int rightTime = 0;
        for (Episode e : eps) {
            boolean seen = all.stream().anyMatch(s -> s.blocked() > 0 && s.snapshotMs() >= e.blockStartMs() && s.snapshotMs() <= e.blockEndMs());
            boolean seenOnTime = all.stream().anyMatch(s -> s.blocked() > 0 && s.snapshotMs() >= e.blockStartMs() && s.snapshotMs() <= e.blockEndMs()
                    && s.intendedMs() >= e.blockStartMs() - INTERVAL_MS && s.intendedMs() <= e.blockEndMs());
            if (seen) {
                captured++;
            }
            if (seenOnTime) {
                rightTime++;
            }
        }
        List<Long> skews = all.stream().map(s -> s.snapshotMs() - s.intendedMs()).sorted().toList();
        long lastAfter = all.stream().mapToLong(Sample::snapshotMs).max().orElse(t0) - (t0 + WINDOW_MS);
        int inWindow = (int) all.stream().filter(s -> s.snapshotMs() - t0 < WINDOW_MS).count();
        Result r = new Result(policy, delayMs, all.size(), inWindow, eps.size(), captured, rightTime,
                skews.isEmpty() ? 0 : skews.get(skews.size() / 2), skews.isEmpty() ? 0 : skews.get(skews.size() - 1),
                maxConcurrent.get(), lastAfter);
        System.out.println("[E4] " + r);
        return r;
    }

    private static Sample sampleOnce(Connection c, long intended, long delayMs, AtomicInteger concurrent, AtomicInteger maxConcurrent)
            throws Exception {
        int now = concurrent.incrementAndGet();
        maxConcurrent.accumulateAndGet(now, Math::max);
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            long snapshot = System.currentTimeMillis();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'e4-victim'"
                         + " AND cardinality(pg_blocking_pids(pid)) > 0")) {
                rs.next();
                return new Sample(intended, snapshot, rs.getInt(1));
            }
        } finally {
            concurrent.decrementAndGet();
        }
    }

    /** 4초마다 한 세션이 행을 잡고 1.5초 쥔다. 그동안 다른 세션이 같은 행을 고치려다 막힌다. */
    private static void generateEpisodes(long t0, List<Episode> episodes, AtomicBoolean stop) {
        try (Connection holder = open("e4-holder"); Connection victim = open("e4-victim")) {
            holder.setAutoCommit(false);
            ExecutorService victimRunner = Executors.newSingleThreadExecutor();
            long next = t0 + 500;
            while (!stop.get() && next - t0 < WINDOW_MS - EPISODE_HOLD_MS) {
                long wait = next - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                try (Statement st = holder.createStatement()) {
                    st.executeUpdate("UPDATE exp_bp SET v = v + 1 WHERE id = 1");
                }
                var blocked = victimRunner.submit(() -> {
                    try (Statement st = victim.createStatement()) {
                        st.executeUpdate("UPDATE exp_bp SET v = v + 1 WHERE id = 1");
                    }
                    return null;
                });
                Thread.sleep(100); // 피해자 문장이 실제로 락 대기에 들어갈 시간
                long blockStart = System.currentTimeMillis();
                Thread.sleep(EPISODE_HOLD_MS - 100);
                long blockEnd = System.currentTimeMillis();
                holder.commit();
                blocked.get(5, TimeUnit.SECONDS);
                episodes.add(new Episode(blockStart, blockEnd));
                next += EPISODE_EVERY_MS;
            }
            victimRunner.shutdown();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String document(List<Result> results, String startedAt) {
        StringBuilder md = new StringBuilder("# E4: 활성 세션 샘플러가 느려질 때의 백프레셔 정책 비교\n\n");
        md.append("이 문서와 `ash-backpressure.csv`는 `AshBackpressureExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.\n\n");
        md.append("- 실행 일시: ").append(startedAt).append('\n');
        md.append("- 대상: PostgreSQL 16 (127.0.0.1:15432/sample). 4초마다 한 세션이 행을 약 1.4초 잡아 다른 세션을 막는다(막힘 사건)\n");
        md.append("- 샘플 1회 = 막힌 피해자 세션 수 조회. 계획 간격 1초, 창 ").append(WINDOW_MS / 1000).append("초. 느린 대상은 조회 앞 지연 주입(0ms, 2000ms)\n");
        md.append("- DELAY: 끝난 뒤 1초 쉬고 다음 틱(제품 방식). QUEUE: 1초 계획을 지키려 밀린 틱을 쌓아 순서대로 처리. PARALLEL: 기다리지 않고 매초 새 조회\n");
        md.append("- 잡은 사건: 사건 동안 찍힌 샘플이 막힘을 봄. 계획 시각도 사건 안: 그 샘플에 기록되는 시각(제품은 틱 시작 시각을 기록한다)도 사건 시작 1초 전 ~ 끝 안에 든다. 수집이 2초 걸리면 기록 시각이 실제 조회보다 2초 앞서 이 칸이 0이 된다\n");
        md.append("- 한계: 주입 지연은 부하와 무관하다. 실제 과부하에서는 조회가 겹칠수록 대상이 더 느려질 수 있는데 이 실험은 그 되먹임을 재지 않는다\n\n");
        md.append("| 지연 | 정책 | 창 안 샘플 | 막힘 사건 | 잡은 사건 | 계획 시각도 사건 안 | 실제 조회 시각 - 계획 시각 중앙값 | 최대 | 동시 대상 조회 최대 | 창이 끝난 뒤 마지막 샘플 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            md.append(String.format(Locale.ROOT, "| %dms | %s | %d | %d | %d | %d | %dms | %dms | %d | %s |%n",
                    r.delayMs(), r.policy(), r.samplesInWindow(), r.episodes(), r.captured(), r.capturedAtRightTime(),
                    r.skewP50Ms(), r.skewMaxMs(), r.maxConcurrent(),
                    r.lastSnapshotAfterWindowMs() > 0 ? "+" + r.lastSnapshotAfterWindowMs() + "ms" : "-"));
        }
        return md.toString();
    }
}
