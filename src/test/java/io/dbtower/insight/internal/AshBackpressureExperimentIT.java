package io.dbtower.insight.internal;

import io.dbtower.testsupport.TargetTableLock;
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

    /** NONE: 관측을 아예 돌리지 않는 기준선. 앱 부하만 걸어 대상의 정상 지연을 잰다 */
    enum Policy { NONE, DELAY, QUEUE, PARALLEL }

    record Sample(long intendedMs, long snapshotMs, int blocked) {
    }

    record Episode(long blockStartMs, long blockEndMs) {
    }

    record Result(Policy policy, long delayMs, int samples, int samplesInWindow, int episodes, int captured,
                  int capturedAtRightTime, long skewP50Ms, long skewMaxMs, int maxConcurrent, long lastSnapshotAfterWindowMs,
                  int appQueries, long appP50Ms, long appP95Ms, long appP99Ms,
                  double targetCpuAvgPct, int targetCpuSamples, long targetReadMb) {
    }

    private static Connection open(String app) throws SQLException {
        Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
        try (Statement st = c.createStatement()) {
            st.execute("SET application_name = '" + app + "'");
        }
        return c;
    }

    /** 같은 대상 DB에 다른 실행이 붙어 exp_bp 를 지우지 않게 잡는다(#112) */
    private static TargetTableLock targetLock;

    @BeforeAll
    static void setUp() throws Exception {
        targetLock = TargetTableLock.acquireIfEnabled("DBTOWER_EXPERIMENT", List.of(
                new TargetTableLock.Target("jdbc:postgresql://127.0.0.1:15432/sample", "postgres", "dbtower1234")));
        try (Connection c = open("e4-setup"); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS exp_bp");
            st.execute("CREATE TABLE exp_bp (id INT PRIMARY KEY, v INT)");
            st.execute("INSERT INTO exp_bp VALUES (1, 0)");
            st.execute("DROP TABLE IF EXISTS exp_app");
            st.execute("CREATE TABLE exp_app (id INT PRIMARY KEY, v INT)");
            st.execute("INSERT INTO exp_app SELECT g, g FROM generate_series(1, 2000) g");
        }
    }

    @AfterAll
    static void releaseLock() {
        if (targetLock != null) {
            targetLock.close();
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
        assertTrue(results.stream().filter(r -> r.delayMs() == 0 && r.policy() != Policy.NONE).allMatch(r -> r.captured() > 0),
                "지연이 없을 때도 한 사건도 못 잡았다 — 샘플러 조회가 막힘을 보지 못하는 설정이다");
    }

    private Result runOnce(Policy policy, long delayMs, StringBuilder csv) throws Exception {
        List<Episode> episodes = Collections.synchronizedList(new ArrayList<>());
        List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean stop = new AtomicBoolean();
        long t0 = System.currentTimeMillis();

        Thread generator = new Thread(() -> generateEpisodes(t0, episodes, stop), "e4-episodes");
        generator.start();

        // 앱 부하: 짧은 조회를 창 동안 계속 돌려 대상의 정상 지연을 잰다. 샘플러가 대상을 얼마나 더 느리게 만드는지가 이 축이다.
        List<Long> appLatency = Collections.synchronizedList(new ArrayList<>());
        Thread app = new Thread(() -> appLoad(t0, stop, appLatency), "e4-app");
        app.start();

        // 대상 자원: 컨테이너 CPU%와 DB 디스크 읽기를 같은 창에서 모은다. 관측이 대상을 태우는지를 이 축으로 본다.
        List<Double> targetCpu = Collections.synchronizedList(new ArrayList<>());
        Thread host = new Thread(() -> sampleTargetCpu(t0, stop, targetCpu), "e4-host");
        host.start();
        long readStart = targetReadBlocks();

        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        switch (policy) {
            case NONE -> Thread.sleep(WINDOW_MS);
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
        app.join(10_000);
        host.join(10_000);
        long readBlocks = targetReadBlocks() - readStart;

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
        List<Long> appSamples = new ArrayList<>(appLatency);
        Result r = new Result(policy, delayMs, all.size(), inWindow, eps.size(), captured, rightTime,
                skews.isEmpty() ? 0 : skews.get(skews.size() / 2), skews.isEmpty() ? 0 : skews.get(skews.size() - 1),
                maxConcurrent.get(), lastAfter,
                appSamples.size(), pct(appSamples, 0.50), pct(appSamples, 0.95), pct(appSamples, 0.99),
                targetCpu.isEmpty() ? -1 : targetCpu.stream().mapToDouble(Double::doubleValue).average().orElse(-1),
                targetCpu.size(), readBlocks * 8 / 1024);
        System.out.println("[E4] " + r);
        return r;
    }

    /** 대상 컨테이너 CPU%를 2초마다 샘플. docker 가 없으면 -1 을 넣지 않고 건너뛴다. */
    private static void sampleTargetCpu(long t0, AtomicBoolean stop, List<Double> cpu) {
        while (!stop.get() && System.currentTimeMillis() - t0 < WINDOW_MS) {
            try {
                Process p = new ProcessBuilder("docker", "stats", "--no-stream", "--format", "{{.CPUPerc}}", "dbtower-postgres")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                p.waitFor(10, TimeUnit.SECONDS);
                String pctOnly = out.replace("%", "").trim();
                if (!pctOnly.isEmpty() && !out.contains("Error")) {
                    cpu.add(Double.parseDouble(pctOnly));
                }
            } catch (Exception ignored) {
                return;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** 대상 DB 디스크 읽기 블록 누적값. 관측 조회가 캐시를 타는지 디스크를 쓰는지 이 축으로 본다. */
    private static long targetReadBlocks() {
        try (Connection c = open("e4-host"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT blks_read FROM pg_stat_database WHERE datname = 'sample'")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 앱 조회 지연을 백분위로. 표본이 비면 0. */
    private static long pct(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(p * sorted.size())));
    }

    /** 앱 부하 한 줄기: 창 동안 같은 조회를 반복해 지연 분포를 모은다. 관측이 없을 때(NONE)와 겹칠 때를 같은 축으로 비교한다. */
    private static void appLoad(long t0, AtomicBoolean stop, List<Long> latencies) {
        try (Connection c = open("e4-app")) {
            while (!stop.get() && System.currentTimeMillis() - t0 < WINDOW_MS) {
                long start = System.nanoTime();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT count(*) FROM exp_app WHERE id > 0")) {
                    rs.next();
                }
                latencies.add((System.nanoTime() - start) / 1_000_000);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
        md.append("- 잡은 사건: 사건 동안 찍힌 샘플이 막힘을 봄. 기록 시각도 사건 안: 그 샘플에 제품이 기록하는 시각도 사건 시작 1초 전 ~ 끝 안에 든다\n");
        md.append("  - 수정 전(틱 시작 시각을 기록): 수집이 2초 걸리면 기록 시각이 실제 조회보다 2초 앞서 이 칸이 0이 됐다\n");
        md.append("  - 수정 후(#98, 인스턴스를 실제로 조회한 시각을 기록): 기록 시각 = 실제 조회 시각이라 잡은 사건과 같다. 제품의 기록 시각과 실제 조회 시각의 차이는 `AshSamplerObservedTimeTest`가 잰다\n");
        md.append("- 앱 조회: 같은 창 동안 `exp_app` 에 짧은 조회를 반복해 지연 분포를 잰다. `NONE`(관측 없음) 기준선과 비교해 **관측이 대상을 얼마나 더 느리게 만드는지**를 본다\n");
        md.append("- 대상 자원: 컨테이너 CPU%(docker stats, 2초 간격)와 DB 디스크 읽기(pg_stat_database.blks_read)를 같은 창에서 잰다\n");
        md.append("- 한계: 주입 지연은 부하와 무관하다. 앱 조회는 캐시에 적중하는 짧은 조회라 디스크 축은 거의 0으로 나온다\n\n");
        md.append("| 지연 | 정책 | 앱 조회 p50 | 앱 조회 p95 | 앱 조회 p99 | 앱 조회 수 | 대상 CPU 평균 | 표본 | 대상 디스크 읽기 | 창 안 샘플 | 막힘 사건 | 잡은 사건 | 기록 시각도 사건 안(수정 전: 틱 시작) | 기록 시각도 사건 안(수정 후: 실제 조회) | 실제 조회 시각 - 계획 시각 중앙값 | 최대 | 동시 대상 조회 최대 | 창이 끝난 뒤 마지막 샘플 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            md.append(String.format(Locale.ROOT, "| %dms | %s | %dms | %dms | %dms | %d | %s | %s | %s | %d | %d | %d | %d | %dms | %dms | %d | %s |%n",
                    r.delayMs(), r.policy(), r.appP50Ms(), r.appP95Ms(), r.appP99Ms(), r.appQueries(),
                    r.targetCpuAvgPct() < 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", r.targetCpuAvgPct()),
                    r.targetCpuSamples() == 0 ? "-" : String.valueOf(r.targetCpuSamples()),
                    r.targetReadMb() + "MB",
                    r.samplesInWindow(), r.episodes(), r.captured(), r.capturedAtRightTime(), r.skewP50Ms(), r.skewMaxMs(), r.maxConcurrent(),
                    r.lastSnapshotAfterWindowMs() > 0 ? "+" + r.lastSnapshotAfterWindowMs() + "ms" : "-"));
        }
        return md.toString();
    }
}
