package io.dbtower.operator.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E6b — 배치 사이 쉼(pause)이 복제가 따라오는 데 실제로 도움이 되는가.
 *
 * <p>E6(bulk-change-scale)은 단일 인스턴스에서 배치와 한 트랜잭션을 비교해, 1,000행 배치·100ms 쉼이
 * 남의 쓰기를 덜 민다는 것을 쟀다. 하지만 그 100ms가 <b>복제를 따라오게 하는 데</b> 실제로 쓰이는지는
 * 재지 않았다(복제 환경이 없었다). 여기서 primary/replica를 세우고 그 축을 채운다.
 *
 * <p>질문은 하나다. 1,000행씩 커밋할 때 사이에 쉬는 것이 replica의 지연을 실제로 줄이는가,
 * 그 대가는 총 작업 시간과 writer 대기로 얼마인가.
 *
 * <p>측정: ① 총 작업 시간 ② 동시 writer의 커밋 대기(p50/p95/max) ③ 배치 중 replica replay lag(최대·평균, ms)
 *
 * <p>게이트: {@code DBTOWER_EXPERIMENT=1 ./gradlew cleanTest test --tests '*ReplicationPauseExperimentIT'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_EXPERIMENT", matches = "1")
class ReplicationPauseExperimentIT {

    private static final String PRIMARY = "jdbc:postgresql://127.0.0.1:16432/sample";
    private static final String REPLICA = "jdbc:postgresql://127.0.0.1:16433/sample";
    private static final String USER = "postgres";
    private static final String PASSWORD = "dbtower1234";
    private static final int ROWS = 1_000_000;
    private static final int BATCH_ROWS = 1_000;
    private static final long[] PAUSES_MS = {0, 100, 300};
    /** 동시 작성자 수. 0이면 쉼 자체의 효과만 본다 — 작성자가 계속 쓰면 쉼은 "다른 writer에게 준 시간"이 된다 */
    private static final int[] WRITER_COUNTS = {0, 2};
    private static final int TIMEOUT_SECONDS = 120;
    private static final Path DOC = Path.of("docs", "experiments", "replication-pause.md");
    private static final Path CSV = Path.of("docs", "experiments", "replication-pause.csv");

    record Run(long pauseMs, int writers, long totalMillis, int batches, long affected,
               long lagMaxMs, long lagAvgMs, int lagSamples,
               double writerP50Ms, double writerP95Ms, double writerMaxMs, int writerCommits) {
    }

    @Test
    void 배치_사이_쉼이_복제_지연을_줄이는지_잰다() throws Exception {
        String startedAt = OffsetDateTime.now().toString();
        assertTrue(replicaReady(), "replica(e6-pg-replica, 16433)가 스트리밍 복제 중이 아니다 — 먼저 세워라");
        seed();

        List<Run> runs = new ArrayList<>();
        StringBuilder csv = new StringBuilder("pause_ms,writers,step,event,millis_or_lag\n");
        for (int writers : WRITER_COUNTS) {
            for (long pause : PAUSES_MS) {
                runs.add(runOnce(pause, writers, csv));
            }
        }
        Files.writeString(CSV, csv, StandardCharsets.UTF_8);
        Files.writeString(DOC, document(runs, startedAt), StandardCharsets.UTF_8);
        System.out.println("[E6b] 결과 기록: " + DOC.toAbsolutePath());

        assertTrue(runs.stream().allMatch(r -> r.affected() == ROWS), "모든 배치가 100만 행을 바꿔야 한다");
        assertTrue(runs.stream().allMatch(r -> r.lagSamples() > 0), "replica lag 표본이 하나도 없다 — 복제 대상이 서 있지 않다");
    }

    private Run runOnce(long pauseMs, int writers, StringBuilder csv) throws Exception {
        resetNote();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(Math.max(writers, 1));
        List<Long> writerMicros = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < writers; i++) {
            Thread.ofPlatform().name("e6b-writer-" + i).start(() -> writer(stop, ready, writerMicros));
        }
        if (writers > 0) {
            assertTrue(ready.await(30, TimeUnit.SECONDS), "동시 작성자가 준비되지 않았다");
        }

        List<Long> lags = new ArrayList<>();
        long affected = 0;
        int batches = 0;
        try (Connection c = DriverManager.getConnection(PRIMARY, USER, PASSWORD);
             Statement st = c.createStatement()) {
            long t0 = System.nanoTime();
            for (long start = 0; start < ROWS; start += BATCH_ROWS) {
                long from = start;
                long to = start + BATCH_ROWS;
                affected += st.executeUpdate(
                        "UPDATE bulk_scale SET note = 'done' WHERE id > " + from + " AND id <= " + to);
                batches++;
                lags.add(replayLagMs(st));
                if (pauseMs > 0) {
                    Thread.sleep(pauseMs);
                }
            }
            long totalMillis = (System.nanoTime() - t0) / 1_000_000;
            stop.set(true);
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().startsWith("e6b-writer-")) {
                    t.join(30_000);
                }
            }
            List<Long> sorted = new ArrayList<>(writerMicros);
            Collections.sort(sorted);
            List<Long> lagSorted = new ArrayList<>(lags);
            Collections.sort(lagSorted);
            for (int i = 0; i < lags.size(); i++) {
                csv.append(String.format(Locale.ROOT, "%d,%d,%d,lag_ms,%d%n", pauseMs, writers, i, lags.get(i)));
            }
            csv.append(String.format(Locale.ROOT, "%d,%d,%d,total_ms,%d%n", pauseMs, writers, batches, totalMillis));
            Run r = new Run(pauseMs, writers, totalMillis, batches, affected,
                    lagSorted.isEmpty() ? 0 : lagSorted.get(lagSorted.size() - 1),
                    lagSorted.isEmpty() ? 0 : (long) lagSorted.stream().mapToLong(Long::longValue).average().orElse(0),
                    lagSorted.size(),
                    percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 1.0), sorted.size());
            System.out.println("[E6b] " + r);
            return r;
        }
    }

    /** primary에서 본 replica의 replay 지연(ms). 표본이 없으면 0. */
    private static long replayLagMs(Statement st) {
        try (ResultSet rs = st.executeQuery(
                "SELECT COALESCE(EXTRACT(EPOCH FROM replay_lag) * 1000, 0) FROM pg_stat_replication")) {
            return rs.next() ? (long) rs.getDouble(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private boolean replicaReady() {
        try (Connection c = DriverManager.getConnection(REPLICA, USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_is_in_recovery()")) {
            return rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
    }

    /** 조건 사이에 note를 되돌린다. 100만 행 한 문장이라 몇 초 걸린다. */
    private void resetNote() throws SQLException {
        try (Connection c = DriverManager.getConnection(PRIMARY, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.setQueryTimeout(TIMEOUT_SECONDS);
            st.executeUpdate("UPDATE bulk_scale SET note = NULL");
        }
        waitForReplicaCatchUp();
    }

    /** 다음 조건을 재기 전에 replica가 따라잡을 때까지 기다린다. 안 기다리면 앞 조건의 lag이 다음 조건에 섞인다. */
    private void waitForReplicaCatchUp() throws SQLException {
        try (Connection c = DriverManager.getConnection(PRIMARY, USER, PASSWORD);
             Statement st = c.createStatement()) {
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                if (replayLagMs(st) <= 50) {
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(PRIMARY, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS bulk_scale");
            st.execute("CREATE TABLE bulk_scale (id BIGINT PRIMARY KEY, kind VARCHAR(4), ver INT, note VARCHAR(20))");
        }
        try (Connection c = DriverManager.getConnection(PRIMARY, USER, PASSWORD)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bulk_scale (id, kind, ver, note) VALUES (?, 'M', 0, NULL)")) {
                for (int i = 1; i <= ROWS; i++) {
                    ps.setLong(1, i);
                    ps.addBatch();
                    if (i % 10_000 == 0) {
                        ps.executeBatch();
                        c.commit();
                    }
                }
                ps.executeBatch();
            }
            c.commit();
            try (Statement st = c.createStatement()) {
                st.execute("ANALYZE bulk_scale");
            }
        }
        waitForReplicaCatchUp();
        System.out.println("[E6b] bulk_scale " + ROWS + "행 준비 완료");
    }

    /** 범위 안 임의 행을 쉬지 않고 고쳐 커밋한다. 각 커밋의 소요가 "남들이 얼마나 기다렸나"다. */
    private void writer(AtomicBoolean stop, CountDownLatch ready, List<Long> micros) {
        try (Connection c = DriverManager.getConnection(PRIMARY, USER, PASSWORD);
             PreparedStatement ps = c.prepareStatement("UPDATE bulk_scale SET ver = ver + 1 WHERE id = ?")) {
            c.setAutoCommit(true);
            ps.setQueryTimeout(TIMEOUT_SECONDS);
            ready.countDown();
            Random random = new Random(20260920L);
            while (!stop.get()) {
                ps.setLong(1, random.nextInt(ROWS) + 1L);
                long t0 = System.nanoTime();
                ps.executeUpdate();
                micros.add((System.nanoTime() - t0) / 1000);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("동시 작성자 실패: " + e.getMessage(), e);
        }
    }

    private static double percentile(List<Long> sortedMicros, double p) {
        if (sortedMicros.isEmpty()) {
            return Double.NaN;
        }
        int idx = (int) Math.ceil(p * sortedMicros.size()) - 1;
        return sortedMicros.get(Math.max(0, Math.min(idx, sortedMicros.size() - 1))) / 1000.0;
    }

    private static String document(List<Run> runs, String startedAt) {
        StringBuilder md = new StringBuilder("# E6b: 배치 사이 쉼이 복제 지연을 줄이는가\n\n");
        md.append("이 문서와 `replication-pause.csv`는 `ReplicationPauseExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.\n\n");
        md.append("- 실행 일시: ").append(startedAt).append('\n');
        md.append("- 대상: PostgreSQL 16 primary(`e6-pg-primary`, 16432) + streaming replica(`e6-pg-replica`, 16433, async)\n");
        md.append("- 변경: `bulk_scale` ").append(ROWS).append("행을 1,000행 배치로 `note` 갱신. 배치 사이 쉼만 바꾼다(0 / 100 / 300ms)\n");
        md.append("- 동시 작성자: 0개(쉼의 순수 효과)와 2개(쉼 동안 다른 writer가 계속 쓰는 현실 조건)를 나눠 잰다. 표의 writer 수치는 그 커밋들의 소요다\n");
        md.append("- replica lag: 배치마다 primary의 `pg_stat_replication.replay_lag`를 읽는다(조건 시작 전 따라잡기를 기다린다)\n");
        md.append("- 읽는 법: 작성자 0 조건이 쉼 자체의 효과를 분리한 것이고, 2 조건은 운영에 가까운 조건이다. 두 표를 나란히 봐야 한다\n\n");
        md.append("| 작성자 | 쉼 | 총 소요 | 배치 수 | 바뀐 행 | replay lag 최대 | replay lag 평균 | 표본 | writer p50 | writer p95 | writer 최대 | writer 커밋 수 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Run r : runs) {
            md.append(String.format(Locale.ROOT,
                    "| %d | %dms | %.1f초 | %d | %d | %dms | %dms | %d | %s | %s | %s | %d |%n",
                    r.writers(), r.pauseMs(), r.totalMillis() / 1000.0, r.batches(), r.affected(),
                    r.lagMaxMs(), r.lagAvgMs(), r.lagSamples(),
                    r.writerCommits() == 0 ? "-" : String.format(Locale.ROOT, "%.2fms", r.writerP50Ms()),
                    r.writerCommits() == 0 ? "-" : String.format(Locale.ROOT, "%.2fms", r.writerP95Ms()),
                    r.writerCommits() == 0 ? "-" : String.format(Locale.ROOT, "%.2fms", r.writerMaxMs()),
                    r.writerCommits()));
        }
        return md.toString();
    }
}
