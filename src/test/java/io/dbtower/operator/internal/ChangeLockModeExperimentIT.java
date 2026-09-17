package io.dbtower.operator.internal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E3 — 변경 실행에서 되돌릴 사본을 잡는 방식 셋을 같은 동시 쓰기 부하에서 나란히 잰다.
 *
 * <pre>
 * PESSIMISTIC : 사본을 FOR UPDATE로 잠가 읽고 변경, 키로 재조회해 행 수 대조 후 커밋 (제품 방식)
 * OPTIMISTIC  : 사본을 잠그지 않고 읽고 변경, 키로 재조회해 바꾸지 않은 열이 사본과 같은지 대조. 다르면 롤백
 * UNCAPTURED  : 사본 없이 변경만 (되돌리기 불가, 대조군)
 * </pre>
 *
 * <p>동시 작성자 둘이 변경 범위 안의 임의 행을 쉬지 않고 고쳐 커밋한다. 하나는 변경이 건드리지 않는 열(grp), 하나는 변경과 같은 열(note)을 바꾸고 둘 다 ver를 올린다. 사본을 뜬 뒤 변경이 그 행에
 * 닿기 전에 커밋된 쓰기가 있으면 사본은 낡는다. 낡음의 원장은 {@code ver} 다 — 재조회한 ver가 사본의 ver와 다르면 낡은 행이다.
 * OPTIMISTIC은 ver를 대조에 넣은 경우(버전 열이 있는 테이블)와 넣지 않은 경우(버전 열이 없는 테이블)를 같은 실행에서 함께 판정한다.
 *
 * <p>게이트: {@code DBTOWER_EXPERIMENT=1 ./gradlew cleanTest test --tests '*ChangeLockModeExperimentIT'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_EXPERIMENT", matches = "1")
class ChangeLockModeExperimentIT {

    private static final String TABLE = "exp_lock_mode";
    private static final int ROWS = 20_000;
    private static final int[] K_VALUES = {1_000, 10_000};
    private static final int WARMUP = 2;
    private static final int RUNS = 7;
    private static final int WRITERS = 2;
    private static final int KEY_BATCH = 1_000;
    /** 작성자가 커밋 사이에 쉬는 시간(ms). 0은 쉬지 않는 높은 부하, 50은 작성자당 초당 약 20건 */
    private static final int[] WRITER_PAUSES = {0, 50};
    private static final Path DOC = Path.of("docs", "experiments", "change-lock-modes.md");
    private static final Path CSV = Path.of("docs", "experiments", "change-lock-modes.csv");

    enum Mode { PESSIMISTIC, OPTIMISTIC, UNCAPTURED }

    record Db(String dbms, String url, String user, String password) {
        Connection open() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }
    }

    record Run(String dbms, int pauseMs, Mode mode, int k, int run, double lockHoldMs, double totalMs, int staleRows,
               boolean committed, boolean wouldAbortWithVersion, boolean wouldAbortWithoutVersion,
               int writerCommits, double writerP50Ms, double writerP95Ms, double writerMaxMs) {
    }

    private static final List<Db> DBS = List.of(
            new Db("MySQL", "jdbc:mysql://127.0.0.1:13306/sample", "root", "dbtower1234"),
            new Db("PostgreSQL", "jdbc:postgresql://127.0.0.1:15432/sample", "postgres", "dbtower1234"));

    @BeforeAll
    static void setUp() throws Exception {
        for (Db db : DBS) {
            try (Connection c = db.open(); Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + TABLE);
                st.execute("CREATE TABLE " + TABLE + " (id BIGINT PRIMARY KEY, grp INT, amount INT, note VARCHAR(64), ver INT)");
                st.execute("CREATE INDEX " + TABLE + "_amount_idx ON " + TABLE + " (amount)");
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " VALUES (?, ?, ?, 'seed', 0)")) {
                    for (int i = 1; i <= ROWS; i++) {
                        ps.setLong(1, i);
                        ps.setInt(2, i % 100);
                        ps.setInt(3, i);
                        ps.addBatch();
                        if (i % 1000 == 0) {
                            ps.executeBatch();
                        }
                    }
                }
                c.commit();
            }
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        for (Db db : DBS) {
            try (Connection c = db.open(); Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + TABLE);
            }
        }
    }

    @Test
    void 사본을_잡는_방식_셋을_같은_동시_쓰기_부하에서_잰다() throws Exception {
        String startedAt = OffsetDateTime.now().toString();
        List<Run> runs = new ArrayList<>();
        Map<String, String> env = new HashMap<>();
        for (Db db : DBS) {
            env.put(db.dbms(), version(db));
            for (int pause : WRITER_PAUSES) {
            for (int k : K_VALUES) {
                for (Mode mode : Mode.values()) {
                    for (int r = 1; r <= WARMUP + RUNS; r++) {
                        Run run = runOnce(db, pause, mode, k, r);
                        if (r > WARMUP) {
                            runs.add(run);
                        }
                    }
                }
            }
            }
        }
        write(runs, env, startedAt);
        for (Run run : runs) {
            if (run.mode() == Mode.PESSIMISTIC) {
                assertEquals(0, run.staleRows(), "잠근 사본은 낡을 수 없다: " + run);
            }
        }
        assertTrue(runs.stream().anyMatch(r -> r.writerCommits() > 0), "동시 작성자가 한 번도 커밋하지 않았다 — 부하가 서지 않은 측정");
    }

    private static Run runOnce(Db db, int pauseMs, Mode mode, int k, int runNo) throws Exception {
        try (Connection reset = db.open(); Statement st = reset.createStatement()) {
            st.executeUpdate("UPDATE " + TABLE + " SET note = 'seed', ver = 0, grp = MOD(id, 100) WHERE id <= " + k);
        }
        AtomicBoolean stop = new AtomicBoolean();
        List<Double> writerMs = Collections.synchronizedList(new ArrayList<>());
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) {
            // 작성자 0은 변경이 건드리지 않는 열(grp), 작성자 1은 변경과 같은 열(note)을 바꾼다. 둘 다 ver를 올린다
            String writeSql = w == 0 ? "UPDATE " + TABLE + " SET grp = grp + 1000, ver = ver + 1 WHERE id = ?"
                    : "UPDATE " + TABLE + " SET note = 'w', ver = ver + 1 WHERE id = ?";
            Thread t = new Thread(() -> {
                try (Connection c = db.open()) {
                    c.setAutoCommit(false);
                    try (PreparedStatement ps = c.prepareStatement(writeSql)) {
                        while (!stop.get()) {
                            long t0 = System.nanoTime();
                            ps.setLong(1, ThreadLocalRandom.current().nextLong(1, k + 1));
                            ps.executeUpdate();
                            c.commit();
                            writerMs.add((System.nanoTime() - t0) / 1e6);
                            if (pauseMs > 0) {
                                Thread.sleep(pauseMs);
                            }
                        }
                    }
                } catch (SQLException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            t.setDaemon(true);
            writers.add(t);
            t.start();
        }
        Thread.sleep(200);
        int before = writerMs.size();

        double lockHoldMs;
        double totalMs;
        int stale = 0;
        boolean committed;
        boolean abortWithVersion = false;
        boolean abortWithoutVersion = false;
        long start = System.nanoTime();
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            Map<Long, int[]> snapshot = new HashMap<>();
            long lockStart;
            if (mode != Mode.UNCAPTURED) {
                String lock = mode == Mode.PESSIMISTIC ? " FOR UPDATE" : "";
                lockStart = System.nanoTime();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id, grp, amount, ver FROM " + TABLE + " WHERE amount <= " + k + lock)) {
                    while (rs.next()) {
                        snapshot.put(rs.getLong(1), new int[]{rs.getInt(2), rs.getInt(3), rs.getInt(4)});
                    }
                }
                if (mode == Mode.OPTIMISTIC) {
                    lockStart = System.nanoTime();
                }
            } else {
                lockStart = System.nanoTime();
            }
            int affected;
            try (Statement st = c.createStatement()) {
                affected = st.executeUpdate("UPDATE " + TABLE + " SET note = 'exp' WHERE id <= " + k);
            }
            if (mode == Mode.UNCAPTURED) {
                committed = true;
            } else {
                List<Long> keys = new ArrayList<>(snapshot.keySet());
                Map<Long, int[]> after = new HashMap<>();
                for (int from = 0; from < keys.size(); from += KEY_BATCH) {
                    List<Long> batch = keys.subList(from, Math.min(keys.size(), from + KEY_BATCH));
                    String in = batch.stream().map(x -> "?").collect(Collectors.joining(","));
                    try (PreparedStatement ps = c.prepareStatement("SELECT id, grp, amount, ver FROM " + TABLE + " WHERE id IN (" + in + ")")) {
                        for (int i = 0; i < batch.size(); i++) {
                            ps.setLong(i + 1, batch.get(i));
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                after.put(rs.getLong(1), new int[]{rs.getInt(2), rs.getInt(3), rs.getInt(4)});
                            }
                        }
                    }
                }
                boolean countsMatch = affected == snapshot.size() && after.size() == snapshot.size();
                for (Map.Entry<Long, int[]> e : snapshot.entrySet()) {
                    int[] now = after.get(e.getKey());
                    if (now == null) {
                        continue;
                    }
                    if (now[2] != e.getValue()[2]) {
                        stale++;
                    }
                    if (now[0] != e.getValue()[0] || now[1] != e.getValue()[1]) {
                        abortWithoutVersion = true;
                    }
                }
                abortWithVersion = stale > 0;
                if (!countsMatch || (mode == Mode.OPTIMISTIC && abortWithVersion)) {
                    c.rollback();
                    committed = false;
                } else {
                    committed = true;
                }
            }
            if (committed) {
                c.commit();
            }
            long end = System.nanoTime();
            lockHoldMs = (end - lockStart) / 1e6;
            totalMs = (end - start) / 1e6;
        }
        Thread.sleep(100);
        stop.set(true);
        for (Thread t : writers) {
            t.join(10_000);
        }
        List<Double> during = new ArrayList<>(writerMs.subList(before, writerMs.size()));
        Collections.sort(during);
        return new Run(db.dbms(), pauseMs, mode, k, runNo, lockHoldMs, totalMs, stale, committed, abortWithVersion, abortWithoutVersion,
                during.size(), pct(during, 50), pct(during, 95), during.isEmpty() ? 0 : during.get(during.size() - 1));
    }

    private static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static String version(Db db) throws SQLException {
        try (Connection c = db.open()) {
            return c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion()
                    + ", 격리 수준 " + c.getTransactionIsolation();
        }
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.isEmpty() ? Double.NaN : s.get(s.size() / 2);
    }

    private static void write(List<Run> runs, Map<String, String> env, String startedAt) throws Exception {
        StringBuilder csv = new StringBuilder("dbms,writer_pause_ms,mode,k,run,lock_hold_ms,total_ms,stale_rows,committed,abort_with_version,"
                + "abort_without_version,writer_commits,writer_p50_ms,writer_p95_ms,writer_max_ms\n");
        for (Run r : runs) {
            csv.append(String.format(Locale.ROOT, "%s,%d,%s,%d,%d,%.2f,%.2f,%d,%b,%b,%b,%d,%.2f,%.2f,%.2f%n", r.dbms(), r.pauseMs(), r.mode(), r.k(),
                    r.run(), r.lockHoldMs(), r.totalMs(), r.staleRows(), r.committed(), r.wouldAbortWithVersion(),
                    r.wouldAbortWithoutVersion(), r.writerCommits(), r.writerP50Ms(), r.writerP95Ms(), r.writerMaxMs()));
        }
        Files.writeString(CSV, csv, StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder("# E3: 되돌릴 사본을 잡는 방식 셋의 비교\n\n");
        md.append("이 문서와 `change-lock-modes.csv`는 `ChangeLockModeExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.\n\n");
        md.append("- 실행 일시: ").append(startedAt).append('\n');
        env.forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append('\n'));
        md.append("- 테이블 ").append(ROWS).append("행, 변경 `UPDATE ... SET note = 'exp' WHERE id <= k`, 사본 조건 `amount <= k`(인덱스 있음)\n");
        md.append("- 동시 작성자 ").append(WRITERS).append("개(커밋 사이 쉼 0ms 또는 50ms)가 범위 안 임의 행을 쉬지 않고 고쳐 커밋(하나는 `grp`, 하나는 변경과 같은 열 `note`, 둘 다 `ver + 1`). 조합마다 워밍업 ")
                .append(WARMUP).append("회 버리고 ").append(RUNS).append("회\n");
        md.append("- 락 보유: PESSIMISTIC은 사본 조회 시작부터, OPTIMISTIC·UNCAPTURED는 변경 문장 시작부터 커밋(또는 롤백)까지\n");
        md.append("- 낡은 행: 재조회한 ver가 사본의 ver와 다른 행. 사본을 뜬 뒤 변경 전에 다른 커밋이 끼어든 행이다\n");
        md.append("- 버전 열 없이 대조: 바꾸지 않은 열(grp, amount)만 사본과 비교한다. note만 바뀐 행은 변경이 덮어써 비교로 드러나지 않는다\n\n");
        md.append("| 기종 | 작성자 쉼(ms) | k | 방식 | 락 보유 중앙값(ms) | 작성자 p95(ms) | 작성자 최대(ms) | 낡은 행이 생긴 실행 | 커밋된 실행 | 버전 열 없이 대조했다면 낡은 사본으로 커밋 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Db db : DBS) {
            for (int pause : WRITER_PAUSES) {
            for (int k : K_VALUES) {
                for (Mode mode : Mode.values()) {
                    List<Run> rs = runs.stream().filter(r -> r.dbms().equals(db.dbms()) && r.pauseMs() == pause && r.k() == k && r.mode() == mode).toList();
                    long staleRuns = rs.stream().filter(r -> r.staleRows() > 0).count();
                    long committedRuns = rs.stream().filter(Run::committed).count();
                    long silentWrong = mode == Mode.OPTIMISTIC
                            ? rs.stream().filter(r -> r.staleRows() > 0 && !r.wouldAbortWithoutVersion()).count() : 0;
                    md.append(String.format(Locale.ROOT, "| %s | %d | %d | %s | %.1f | %.1f | %.1f | %d/%d | %d/%d | %s |%n",
                            db.dbms(), pause, k, mode, median(rs.stream().map(Run::lockHoldMs).toList()),
                            median(rs.stream().map(Run::writerP95Ms).toList()),
                            rs.stream().mapToDouble(Run::writerMaxMs).max().orElse(0),
                            staleRuns, rs.size(), committedRuns, rs.size(),
                            mode == Mode.OPTIMISTIC ? silentWrong + "/" + rs.size() : "-"));
                }
            }
            }
        }
        md.append("\n- 낡은 행 수(실행별)는 CSV의 `stale_rows`에 있다\n");
        Files.writeString(DOC, md, StandardCharsets.UTF_8);
        System.out.println("[E3] 결과 기록: " + DOC.toAbsolutePath());
    }
}
