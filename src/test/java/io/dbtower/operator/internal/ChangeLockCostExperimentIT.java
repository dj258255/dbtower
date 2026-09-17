package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.model.ChangeOutcome;
import io.dbtower.operator.model.ChangePlan;
import io.dbtower.operator.model.ChangePlan.Kind;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1 — 변경 실행 안전장치(사본 캡처 - 영향 행 대조 - 재조회 - 커밋)가 대상 DB에 주는 락 비용을 실측한다.
 * 대조군은 사본 없는 실행({@link Kind#UNCAPTURED})이다. 제품 코드는 건드리지 않는다: 실제
 * {@link JdbcChangeRunner}(오퍼레이터의 changeRunner())를 꺼내 쓰고, 커넥션만 프록시로 감싸
 * "잠그는 첫 문장 시작"과 "커밋 완료"를 {@code System.nanoTime()}으로 기록한다.
 *
 * <p>측정 1 = 락 보유 시간(기종 x 방식 x 대상 행 수 k x 조건 인덱스 유무). 측정 2 = 동시 작성자(같은
 * 조합의 k=1000)가 대상 안 UPDATE / 대상 밖 UPDATE / 대상 범위 안 INSERT를 할 때의 대기와 결과.
 * 두 번째 커넥션은 추측 지연 없이 "잠그는 첫 문장이 끝났다"는 신호(CountDownLatch) 뒤에 출발한다.
 * 모든 탐침에는 같은 문장을 변경 없이 단독 실행한 기준선을 함께 잰다 — 대기가 락 때문인지 문장 자체가
 * 느린 것인지 가르는 대조군이다.
 *
 * <p>게이트: {@code DBTOWER_EXPERIMENT=1 ./gradlew test --tests '*ChangeLockCostExperimentIT'}
 * docker compose의 대상 DB(MySQL 13306, PostgreSQL 15432)가 필요하다. 결과는
 * docs/experiments/change-lock-cost.md 와 .csv 로 생성된다(값을 손으로 고치지 않는다).
 *
 * <p>실험 테이블 exp_change_lock 하나만 만들고 {@code @AfterAll}에서 반드시 지운다 — sample의 기존
 * 테이블은 건드리지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_EXPERIMENT", matches = "1")
class ChangeLockCostExperimentIT {

    static final String GATE = "DBTOWER_EXPERIMENT";

    private static final String TABLE = "exp_change_lock";
    private static final String INDEX_NAME = "exp_change_lock_amount_idx";
    private static final String NOTE_SEED = "seed";
    private static final String NOTE_CHANGED = "exp";
    private static final String NOTE_PROBE = "probe";

    /** 실험 테이블 행 수. grp는 0~99를 정확히 200행씩(균등), amount는 행마다 고유값 1..20000이다. */
    private static final int ROWS = 20_000;

    /** 제품 기본 사본 상한과 같은 값(ChangePlan.maxRows). */
    private static final int MAX_ROWS = 10_000;

    private static final int WARMUPS = 2;
    private static final int RUNS = 7;
    private static final int[] K_VALUES = {10, 100, 1_000, 10_000};

    /** 동시 작성자 측정은 이 k 하나만 본다. */
    private static final int CONCURRENT_K = 1_000;

    /** 동시 작성자 탐침과 그 기준선(변경 없이 단독 실행)의 반복 수. */
    private static final int PROBE_REPEATS = 3;

    private static final int PLAN_TIMEOUT_SECONDS = 30;

    /** 두 번째(동시 작성자) 커넥션의 락 대기 상한(초). */
    private static final int PROBE_LOCK_WAIT_SECONDS = 10;

    private static final long PROBE_INSERT_ID = 999_999L;

    private static final List<String> VARIANTS = List.of("indexed", "unindexed");
    private static final List<Kind> KINDS = List.of(Kind.UPDATE, Kind.UNCAPTURED);

    private static final List<Probe> PROBES = List.of(
            new Probe("update_in_range", "UPDATE " + TABLE + " SET note = '" + NOTE_PROBE + "' WHERE id = " + CONCURRENT_K),
            new Probe("update_out_of_range", "UPDATE " + TABLE + " SET note = '" + NOTE_PROBE + "' WHERE id = " + ROWS),
            new Probe("insert_in_range", "INSERT INTO " + TABLE + " (id, grp, amount, note) VALUES ("
                    + PROBE_INSERT_ID + ", 0, 5, '" + NOTE_PROBE + "')"));

    private static final ConnectionPools POOLS =
            new ConnectionPools(new VaultCredentials("", ""), 15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    private static final List<Db> DBS = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        DBS.clear();
        DBS.add(new Db("MySQL", new MySqlOperator(instance(9401, DbmsType.MYSQL, 13306, "sample"), POOLS, null, null),
                new ConsoleCredential("root", "dbtower1234")));
        DBS.add(new Db("PostgreSQL", new PostgresOperator(instance(9402, DbmsType.POSTGRESQL, 15432, "sample"), POOLS, null),
                new ConsoleCredential("postgres", "dbtower1234")));
        for (Db db : DBS) {
            prepareTable(db);
        }
    }

    @AfterAll
    static void tearDown() {
        for (Db db : DBS) {
            try {
                exec(db, "DROP TABLE IF EXISTS " + TABLE);
                System.out.println("[E1] " + db.dbms() + " " + TABLE + " 정리 완료");
            } catch (SQLException e) {
                System.out.println("[E1] " + db.dbms() + " " + TABLE + " 정리 실패: " + e.getMessage());
            }
        }
        POOLS.closeAll();
    }

    @Test
    void 변경_실행_안전장치의_락_비용을_잰다() throws Exception {
        String startedAt = OffsetDateTime.now().toString();
        List<Env> envs = new ArrayList<>();
        List<LockRun> lockRuns = new ArrayList<>();
        List<ProbeSample> probeSamples = new ArrayList<>();
        List<PlanRow> planRows = new ArrayList<>();

        for (Db db : DBS) {
            Env env = environment(db);
            envs.add(env);
            assertEquals(ROWS, env.rows(), db.dbms() + " 실험 테이블이 " + ROWS + "행이 아니다");
            for (String variant : VARIANTS) {
                setIndex(db, "indexed".equals(variant));
                for (int k : K_VALUES) {
                    planRows.add(capturePlan(db, variant, k));
                    planRows.add(reselectPlan(db, variant, k));
                    for (Kind kind : KINDS) {
                        sweep(db, variant, kind, k, lockRuns);
                    }
                }
                for (Probe probe : PROBES) {
                    for (int repeat = 1; repeat <= PROBE_REPEATS; repeat++) {
                        probeSamples.add(baseline(db, variant, probe, repeat));
                    }
                }
                for (Kind kind : KINDS) {
                    for (Probe probe : PROBES) {
                        for (int repeat = 1; repeat <= PROBE_REPEATS; repeat++) {
                            probeSamples.add(concurrent(db, variant, kind, probe, repeat));
                        }
                    }
                }
            }
            setIndex(db, false);
            assertEquals(ROWS, countRows(db), db.dbms() + " 탐침 행이 남아 실험 테이블 행 수가 달라졌다");
        }

        writeOutputs(envs, lockRuns, probeSamples, planRows, startedAt);

        long measured = lockRuns.stream().filter(r -> "measured".equals(r.phase()) && r.lockHoldMs() != null).count();
        System.out.println("[E1] 측정 표본 " + measured + "개(워밍업 제외), 동시 작성자 탐침 표본 "
                + probeSamples.stream().filter(s -> "concurrent".equals(s.section())).count() + "개");
        assertTrue(measured > 0, "성공한 측정이 하나도 없다");
        assertFalse(Files.notExists(Path.of("docs", "experiments", "change-lock-cost.md")), "결과 문서가 없다");
        assertFalse(Files.notExists(Path.of("docs", "experiments", "change-lock-cost.csv")), "원자료가 없다");
    }

    // ---------------------------------------------------------------- 측정 1

    /** 한 조합(기종 x 인덱스 x 방식 x k)을 워밍업 2회 + 7회 반복한다. 반복마다 대상을 원래 값으로 되돌린다. */
    private static void sweep(Db db, String variant, Kind kind, int k, List<LockRun> out) throws SQLException {
        for (int run = 1; run <= WARMUPS + RUNS; run++) {
            String phase = run <= WARMUPS ? "warmup" : "measured";
            String resetError = reset(db, k);
            if (resetError != null) {
                out.add(new LockRun(db.dbms(), variant, kind.name(), k, phase, run, null, null, null, null, "복원 실패: " + resetError));
                continue;
            }
            Timings t = new Timings();
            Attempt attempt = runChange(db, kind, k, PLAN_TIMEOUT_SECONDS, t);
            Double hold = attempt.ok() && t.lockStartNanos > 0 && t.commitEndNanos > 0
                    ? (t.commitEndNanos - t.lockStartNanos) / 1_000_000.0 : null;
            Double statement = attempt.ok() && t.lockStartNanos > 0 && t.lockEndNanos > 0
                    ? (t.lockEndNanos - t.lockStartNanos) / 1_000_000.0 : null;
            Double commit = attempt.ok() && t.commitStartNanos > 0 && t.commitEndNanos > 0
                    ? (t.commitEndNanos - t.commitStartNanos) / 1_000_000.0 : null;
            out.add(new LockRun(db.dbms(), variant, kind.name(), k, phase, run, hold, statement, commit,
                    attempt.outcome() == null ? null : attempt.outcome().affectedRows(),
                    attempt.error() == null && hold == null ? "잠그는 문장·커밋 시각을 잡지 못했다" : attempt.error()));
            if ("measured".equals(phase)) {
                System.out.println("[E1] " + db.dbms() + "/" + variant + "/" + kind + "/k=" + k + " 중 " + (run - WARMUPS)
                        + "회차 lock_hold=" + (hold == null ? "실패" : String.format(Locale.ROOT, "%.2fms", hold))
                        + (attempt.error() == null ? "" : " (" + attempt.error() + ")"));
            }
        }
    }

    // ---------------------------------------------------------------- 측정 2

    /**
     * 변경 쪽이 잠그는 첫 문장을 끝낸 순간(프록시의 래치)에 두 번째 커넥션이 출발한다 — sleep으로 순서를
     * 맞추지 않는다. 두 번째 커넥션의 락 대기 상한은 {@value #PROBE_LOCK_WAIT_SECONDS}초다.
     * 남은 락 보유 시간 = 변경의 커밋 완료 − 탐침 출발 시각(둘 다 프록시 타임스탬프)이다.
     */
    private static ProbeSample concurrent(Db db, String variant, Kind kind, Probe probe, int repeat) throws Exception {
        String resetError = reset(db, CONCURRENT_K);
        if (resetError != null) {
            return new ProbeSample("concurrent", db.dbms(), variant, kind.name(), probe.name(), repeat, null, null, null,
                    "SETUP_FAILED", "복원 실패: " + resetError, null, "복원 실패: " + resetError);
        }
        Timings change = new Timings();
        AtomicReference<Long> departNanos = new AtomicReference<>(-1L);
        AtomicReference<Double> waitMs = new AtomicReference<>();
        AtomicReference<String> outcome = new AtomicReference<>("NO_DEPARTURE");
        AtomicReference<String> detail = new AtomicReference<>("");

        Thread writer = new Thread(() -> {
            try (Connection c = DriverManager.getConnection(db.url(), db.cred().username(), db.cred().password())) {
                setProbeLockWait(c, db);
                if (!change.locked.await(60, TimeUnit.SECONDS)) {
                    detail.set("변경 쪽이 잠그는 문장에 도달하지 못했다");
                    return;
                }
                long t0 = System.nanoTime();
                departNanos.set(t0);
                try (Statement st = c.createStatement()) {
                    st.execute(probe.sql());
                    outcome.set("SUCCESS");
                } catch (SQLException e) {
                    outcome.set(lockTimeout(e) ? "LOCK_TIMEOUT" : "ERROR");
                    detail.set(String.format(Locale.ROOT, "sqlstate=%s code=%d msg=%s", e.getSQLState(), e.getErrorCode(), e.getMessage()));
                } finally {
                    waitMs.set((System.nanoTime() - t0) / 1_000_000.0);
                }
            } catch (Exception e) {
                outcome.set("ERROR");
                detail.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "e1-concurrent-writer-" + db.dbms() + "-" + variant + "-" + kind + "-" + probe.name());
        writer.start();

        Attempt attempt = runChange(db, kind, CONCURRENT_K, PLAN_TIMEOUT_SECONDS, change);
        writer.join(120_000);
        if (writer.isAlive()) {
            writer.interrupt();
            outcome.set("ERROR");
            detail.set("탐침 스레드가 끝나지 않았다");
        }

        Boolean departedBeforeCommit = null;
        Double remainingHold = null;
        if (change.commitEndNanos > 0 && departNanos.get() > 0) {
            departedBeforeCommit = departNanos.get() < change.commitEndNanos;
            remainingHold = (change.commitEndNanos - departNanos.get()) / 1_000_000.0;
        }
        String note = detail.get();
        if (attempt.error() != null) {
            note = (note.isEmpty() ? "" : note + " | ") + "변경 실행 오류: " + attempt.error();
        }
        if (Boolean.FALSE.equals(departedBeforeCommit)) {
            note = (note.isEmpty() ? "" : note + " | ") + "출발이 변경 커밋 뒤였다(대기 시간 통계에서 제외)";
        }
        ProbeSample result = new ProbeSample("concurrent", db.dbms(), variant, kind.name(), probe.name(), repeat,
                waitMs.get(), null, remainingHold, outcome.get(), detail.get(), departedBeforeCommit, note);
        System.out.println("[E1] 동시작성자 " + db.dbms() + "/" + variant + "/" + kind + "/" + probe.name() + " " + repeat + "회차"
                + " → " + outcome.get() + " wait=" + (waitMs.get() == null ? "-" : String.format(Locale.ROOT, "%.1fms", waitMs.get()))
                + " 잔여락=" + (remainingHold == null ? "-" : String.format(Locale.ROOT, "%.1fms", remainingHold))
                + " 출발=커밋전 " + departedBeforeCommit + (note.isEmpty() ? "" : " (" + note + ")"));

        exec(db, "UPDATE " + TABLE + " SET note = '" + NOTE_SEED + "' WHERE id IN (" + CONCURRENT_K + ", " + ROWS + ")");
        exec(db, "DELETE FROM " + TABLE + " WHERE id >= 900000");
        return result;
    }

    /** 기준선 — 같은 탐침 문장을 변경 없이 단독 실행한 시간. 대기가 락 때문인지 가르는 대조군이다. */
    private static ProbeSample baseline(Db db, String variant, Probe probe, int repeat) {
        try (Connection c = open(db)) {
            setProbeLockWait(c, db);
            long t0 = System.nanoTime();
            try (Statement st = c.createStatement()) {
                st.execute(probe.sql());
            }
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            exec(db, "UPDATE " + TABLE + " SET note = '" + NOTE_SEED + "' WHERE id IN (" + CONCURRENT_K + ", " + ROWS + ")");
            exec(db, "DELETE FROM " + TABLE + " WHERE id >= 900000");
            return new ProbeSample("probe_baseline", db.dbms(), variant, "-", probe.name(), repeat, ms, null, null,
                    "SUCCESS", "", null, "");
        } catch (SQLException e) {
            return new ProbeSample("probe_baseline", db.dbms(), variant, "-", probe.name(), repeat, null, null, null,
                    "ERROR", e.getMessage(), null, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 러너 실행

    /** 제품 러너를 프록시 커넥션 위에서 그대로 돌린다. 실패는 던지지 않고 결과로 담는다(실패도 측정이다). */
    private static Attempt runChange(Db db, Kind kind, int k, int timeoutSeconds, Timings timings) {
        Predicate<String> isLocking = kind == Kind.UPDATE
                ? sql -> sql.toUpperCase(Locale.ROOT).contains("FOR UPDATE")
                : sql -> sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("UPDATE");
        try (Connection real = DriverManager.getConnection(db.url(), db.cred().username(), db.cred().password());
             Connection proxy = proxied(real, timings, isLocking)) {
            ChangeOutcome outcome = db.runner().execute(proxy, plan(kind, k, timeoutSeconds));
            return new Attempt(outcome, null);
        } catch (Exception e) {
            return new Attempt(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 변경 문장은 {@code WHERE id <= k}, 사본 조회 조건은 {@code WHERE amount <= k}다. amount == id라
     * 같은 행 집합이고, 이렇게 두면 대상 범위 안에 들어오는 INSERT 탐침이 변경의 "영향 행 수 = 사본 행 수"
     * 불변식을 깨뜨려 변경 자체를 롤백시키지 않는다(INSERT 탐침은 사본 조회가 잡는 락만 시험한다).
     */
    private static ChangePlan plan(Kind kind, int k, int timeoutSeconds) {
        String statement = "UPDATE " + TABLE + " SET note = '" + NOTE_CHANGED + "' WHERE id <= " + k;
        if (kind == Kind.UPDATE) {
            return new ChangePlan(Kind.UPDATE, statement, TABLE, TABLE, "WHERE amount <= " + k, null, MAX_ROWS, timeoutSeconds, false);
        }
        return new ChangePlan(Kind.UNCAPTURED, statement, null, null, null, null, MAX_ROWS, timeoutSeconds, false);
    }

    // ---------------------------------------------------------------- 프록시

    private static Connection proxied(Connection real, Timings timings, Predicate<String> isLocking) {
        return (Connection) Proxy.newProxyInstance(ChangeLockCostExperimentIT.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new ConnectionHandler(real, timings, isLocking));
    }

    /** 실험 한 번의 시각 기록. 변경 쪽 스레드가 쓰고 검증 스레드가 읽는다(전부 volatile). */
    private static final class Timings {
        final CountDownLatch locked = new CountDownLatch(1);
        volatile long lockStartNanos = -1;
        volatile long lockEndNanos = -1;
        volatile long commitStartNanos = -1;
        volatile long commitEndNanos = -1;
        volatile String lockedStatement;
    }

    private static final class ConnectionHandler implements InvocationHandler {

        private final Connection target;
        private final Timings timings;
        private final Predicate<String> isLocking;

        ConnectionHandler(Connection target, Timings timings, Predicate<String> isLocking) {
            this.target = target;
            this.timings = timings;
            this.isLocking = isLocking;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("commit".equals(method.getName())) {
                timings.commitStartNanos = System.nanoTime();
                try {
                    return call(method, args);
                } finally {
                    timings.commitEndNanos = System.nanoTime();
                }
            }
            Object result = call(method, args);
            if (result instanceof Statement st) {
                Class<?> type = st instanceof CallableStatement ? CallableStatement.class
                        : st instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(ChangeLockCostExperimentIT.class.getClassLoader(),
                        new Class<?>[]{type}, new StatementHandler(st, timings, isLocking));
            }
            return result;
        }

        private Object call(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /** 문장 실행만 본다 — 잠그는 첫 문장의 시작과 끝을 찍고, 끝나면 래치로 동시 작성자를 깨운다. */
    private static final class StatementHandler implements InvocationHandler {

        private static final Set<String> EXECUTIONS = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate");

        private final Object target;
        private final Timings timings;
        private final Predicate<String> isLocking;

        StatementHandler(Object target, Timings timings, Predicate<String> isLocking) {
            this.target = target;
            this.timings = timings;
            this.isLocking = isLocking;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String sql = EXECUTIONS.contains(method.getName()) ? stringArg(args) : null;
            boolean locking = sql != null && timings.lockStartNanos < 0 && isLocking.test(sql);
            if (locking) {
                timings.lockedStatement = sql;
                timings.lockStartNanos = System.nanoTime();
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } finally {
                if (locking) {
                    timings.lockEndNanos = System.nanoTime();
                    timings.locked.countDown();
                }
            }
        }

        private static String stringArg(Object[] args) {
            if (args == null) {
                return null;
            }
            for (Object arg : args) {
                if (arg instanceof String s) {
                    return s;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- 대상 DB 준비

    private static void prepareTable(Db db) throws SQLException {
        exec(db, "DROP TABLE IF EXISTS " + TABLE);
        exec(db, "CREATE TABLE " + TABLE
                + " (id BIGINT PRIMARY KEY, grp INT NOT NULL, amount INT NOT NULL, note VARCHAR(64) NOT NULL)");
        try (Connection c = open(db);
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (id, grp, amount, note) VALUES (?, ?, ?, ?)")) {
            for (int n = 1; n <= ROWS; n++) {
                ps.setLong(1, n);
                ps.setInt(2, (n - 1) % 100);
                ps.setInt(3, n);
                ps.setString(4, NOTE_SEED);
                ps.addBatch();
                if (n % 1000 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        analyze(db);
        System.out.println("[E1] " + db.dbms() + " " + TABLE + " " + ROWS + "행 준비");
    }

    /** 인덱스 있음/없음 조건을 만든다. 조건 열(amount)에 인덱스가 있으면 사본 조회가 범위 스캔으로 간다. */
    private static void setIndex(Db db, boolean present) throws SQLException {
        try {
            exec(db, db.mysql() ? "DROP INDEX " + INDEX_NAME + " ON " + TABLE : "DROP INDEX IF EXISTS " + INDEX_NAME);
        } catch (SQLException ignored) {
            // 없어서 나는 오류는 정상이다(MySQL은 IF EXISTS가 없다)
        }
        if (present) {
            exec(db, "CREATE INDEX " + INDEX_NAME + " ON " + TABLE + " (amount)");
            analyze(db);
        }
        System.out.println("[E1] " + db.dbms() + " 인덱스 " + (present ? "추가" : "제거"));
    }

    private static void analyze(Db db) throws SQLException {
        exec(db, db.mysql() ? "ANALYZE TABLE " + TABLE : "ANALYZE " + TABLE);
    }

    /** 대상 행을 원래 값으로 되돌리고 실제로 되돌아갔는지 확인한다 — 반복이 같은 상태에서 시작하게. */
    private static String reset(Db db, int k) {
        try {
            exec(db, "UPDATE " + TABLE + " SET note = '" + NOTE_SEED + "' WHERE amount <= " + k);
            long seeded = queryLong(db, "SELECT COUNT(*) FROM " + TABLE + " WHERE amount <= " + k + " AND note = '" + NOTE_SEED + "'");
            return seeded == k ? null : "대상 " + k + "행 중 " + seeded + "행만 원래 값이다";
        } catch (SQLException e) {
            return e.getMessage();
        }
    }

    private static Env environment(Db db) throws SQLException {
        try (Connection c = open(db); Statement st = c.createStatement()) {
            String version = queryString(st, db.mysql() ? "SELECT VERSION()" : "SELECT version()");
            String isolation = queryString(st, db.mysql() ? "SELECT @@transaction_isolation" : "SHOW transaction_isolation");
            return new Env(db.dbms(), version, isolation, queryLong(st, "SELECT COUNT(*) FROM " + TABLE));
        }
    }

    /** 사본 조회가 실제로 어떤 접근 경로를 쓰는지 — 숫자를 읽는 근거로 함께 남긴다. */
    private static PlanRow capturePlan(Db db, String variant, int k) {
        return new PlanRow(db.dbms(), variant, "capture(k=" + k + ")", "amount <= " + k,
                explain(db, "SELECT * FROM " + TABLE + " WHERE amount <= " + k));
    }

    /** 변경 뒤 재조회(JdbcChangeRunner.selectByKeys)는 키 100개를 OR로 묶은 문장을 쓴다 — 같은 모양을 그대로 본다. */
    private static PlanRow reselectPlan(Db db, String variant, int k) {
        int keys = Math.min(k, 100);
        StringBuilder sql = new StringBuilder("SELECT * FROM " + TABLE + " WHERE ");
        for (int i = 1; i <= keys; i++) {
            sql.append(i > 1 ? " OR " : "").append("(id = ").append(i).append(')');
        }
        return new PlanRow(db.dbms(), variant, "reselect(" + keys + "키 OR)", "id IN (" + keys + "키)",
                explain(db, sql.toString()));
    }

    private static String explain(Db db, String sql) {
        try (Connection c = open(db); Statement st = c.createStatement()) {
            StringBuilder out = new StringBuilder();
            try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                int rows = 0;
                while (rs.next() && rows < 3) {
                    if (rows > 0) {
                        out.append(" ; ");
                    }
                    if (db.mysql()) {
                        out.append("type=").append(rs.getString("type")).append(" key=").append(rs.getString("key"))
                                .append(" rows=").append(rs.getString("rows")).append(" extra=").append(rs.getString("Extra"));
                    } else {
                        out.append(rs.getString(1).strip());
                    }
                    rows++;
                }
            }
            return out.isEmpty() ? "계획 없음" : out.toString();
        } catch (SQLException e) {
            return "계획 조회 실패: " + e.getMessage();
        }
    }

    private static void setProbeLockWait(Connection c, Db db) throws SQLException {
        try (Statement st = c.createStatement()) {
            if (db.mysql()) {
                st.execute("SET SESSION innodb_lock_wait_timeout = " + PROBE_LOCK_WAIT_SECONDS);
                st.execute("SET SESSION lock_wait_timeout = " + PROBE_LOCK_WAIT_SECONDS);
            } else {
                st.execute("SET lock_timeout = '" + PROBE_LOCK_WAIT_SECONDS + "s'");
            }
        }
    }

    private static boolean lockTimeout(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return "55P03".equals(state) || msg.contains("Lock wait timeout") || msg.toLowerCase(Locale.ROOT).contains("lock timeout");
    }

    // ---------------------------------------------------------------- 잡일

    private static Connection open(Db db) throws SQLException {
        return DriverManager.getConnection(db.url(), db.cred().username(), db.cred().password());
    }

    private static void exec(Db db, String... statements) throws SQLException {
        try (Connection c = open(db); Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }

    private static long countRows(Db db) {
        try {
            return queryLong(db, "SELECT COUNT(*) FROM " + TABLE);
        } catch (SQLException e) {
            return -1;
        }
    }

    private static long queryLong(Db db, String sql) throws SQLException {
        try (Connection c = open(db); Statement st = c.createStatement()) {
            return queryLong(st, sql);
        }
    }

    private static long queryLong(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static String queryString(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static DatabaseInstance instance(long id, DbmsType type, int port, String dbName) {
        DatabaseInstance instance = new DatabaseInstance("e1-" + id, type, "127.0.0.1", port, dbName, "unused", "unused");
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    // ---------------------------------------------------------------- 결과 기록

    private static void writeOutputs(List<Env> envs, List<LockRun> lockRuns, List<ProbeSample> probes,
                                     List<PlanRow> planRows, String startedAt) throws IOException {
        Path dir = Path.of("docs", "experiments");
        Files.createDirectories(dir);
        Path csv = dir.resolve("change-lock-cost.csv");
        Path md = dir.resolve("change-lock-cost.md");
        Files.writeString(csv, toCsv(lockRuns, probes), StandardCharsets.UTF_8);
        Files.writeString(md, toMarkdown(envs, lockRuns, probes, planRows, startedAt), StandardCharsets.UTF_8);
        System.out.println("[E1] 결과 기록: " + md.toAbsolutePath() + " / " + csv.toAbsolutePath());
    }

    private static String toCsv(List<LockRun> lockRuns, List<ProbeSample> probes) {
        StringBuilder sb = new StringBuilder();
        sb.append("measurement,dbms,index_variant,kind,k,phase,run,lock_hold_ms,statement_ms,commit_ms,affected_rows,"
                + "probe,wait_ms,remaining_hold_ms,outcome,outcome_detail,departed_before_commit,error\n");
        for (LockRun r : lockRuns) {
            sb.append(csvLine("lock_hold", r.dbms(), r.variant(), r.kind(), String.valueOf(r.k()), r.phase(),
                    String.valueOf(r.run()), num(r.lockHoldMs()), num(r.statementMs()), num(r.commitMs()),
                    r.affected() == null ? "" : String.valueOf(r.affected()),
                    "", "", "", "", "", "", r.error()));
        }
        for (ProbeSample p : probes) {
            sb.append(csvLine(p.section(), p.dbms(), p.variant(), p.kind(), String.valueOf(CONCURRENT_K), "measured",
                    String.valueOf(p.repeat()), "", "", "", "", p.probe(), num(p.waitMs()), num(p.remainingHoldMs()),
                    p.outcome(), p.detail(),
                    p.departedBeforeCommit() == null ? "" : p.departedBeforeCommit().toString(), p.note()));
        }
        return sb.toString();
    }

    private static String csvLine(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(fields[i] == null ? "" : fields[i].replace("\"", "\"\"")).append('"');
        }
        return sb.append('\n').toString();
    }

    private static String toMarkdown(List<Env> envs, List<LockRun> lockRuns, List<ProbeSample> probes,
                                     List<PlanRow> planRows, String startedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("# E1: 변경 실행 안전장치의 락 비용 측정\n\n");
        sb.append("이 문서와 `change-lock-cost.csv`는 `ChangeLockCostExperimentIT`가 실제 대상 DB에 대고 측정해 "
                + "생성한 결과다 — 숫자를 손으로 고치지 않는다. 실행 방법: "
                + "`DBTOWER_EXPERIMENT=1 ./gradlew test --tests '*ChangeLockCostExperimentIT'` "
                + "(게이트 환경변수는 Gradle 태스크 입력이 아니라, 이미 한 번 돌린 뒤면 `cleanTest`를 앞에 붙여야 다시 돈다)\n\n");

        sb.append("## 실행 환경\n\n");
        sb.append("| 기종 | 버전 | 격리 수준 | 실험 테이블 행 수 |\n|---|---|---|---|\n");
        for (Env e : envs) {
            sb.append("| ").append(e.dbms()).append(" | ").append(e.version()).append(" | ").append(e.isolation())
                    .append(" | ").append(e.rows()).append(" |\n");
        }
        sb.append("\n- 실행 일시: ").append(startedAt).append('\n');
        sb.append("- 반복: 조합마다 워밍업 ").append(WARMUPS).append("회를 버리고 ").append(RUNS)
                .append("회 측정. 표의 값은 그 ").append(RUNS).append("회의 중앙값과 p95다(7표본에서 최근접 순위 "
                        + "정의상 p95는 최댓값과 같다)\n");
        sb.append("- 대상 행 수 k: ").append(Arrays.toString(K_VALUES)).append(", 사본 상한 maxRows=")
                .append(MAX_ROWS).append("(제품 기본값)\n");
        sb.append("- 변경 문장: `UPDATE ").append(TABLE).append(" SET note='").append(NOTE_CHANGED)
                .append("' WHERE id <= k`, 사본 조회: `SELECT * FROM ").append(TABLE)
                .append(" WHERE amount <= k FOR UPDATE` (amount == id)\n");
        sb.append("- 락 보유 시간 = 커밋 완료 시각 − 잠그는 첫 문장 실행 시작 시각(둘 다 같은 커넥션에서 프록시로 기록). "
                + "CAPTURED의 잠그는 첫 문장은 사본 조회(FOR UPDATE), UNCAPTURED는 변경 문장 자체다\n");
        sb.append("- 실험 테이블: ").append(TABLE).append("(id BIGINT PK, grp INT 0~99 균등, amount INT = id, note VARCHAR(64)), ")
                .append(ROWS).append("행\n\n");

        sb.append("## 표 1: 락 보유 시간(ms)\n\n");
        sb.append("| 기종 | 조건 인덱스 | k | CAPTURED 중앙값 | CAPTURED p95 | UNCAPTURED 중앙값 | UNCAPTURED p95 | 차이(ms) | 배수 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Env e : envs) {
            for (String variant : VARIANTS) {
                for (int k : K_VALUES) {
                    Stat captured = stat(lockRuns, e.dbms(), variant, Kind.UPDATE, k);
                    Stat plain = stat(lockRuns, e.dbms(), variant, Kind.UNCAPTURED, k);
                    sb.append("| ").append(e.dbms()).append(" | ").append(variant).append(" | ").append(k).append(" | ")
                            .append(fmt(captured.medianMs())).append(" | ").append(fmt(captured.p95Ms())).append(" | ")
                            .append(fmt(plain.medianMs())).append(" | ").append(fmt(plain.p95Ms())).append(" | ")
                            .append(captured.n() == 0 || plain.n() == 0 ? "-" : String.format(Locale.ROOT, "%+.2f", captured.medianMs() - plain.medianMs()))
                            .append(" | ")
                            .append(captured.n() == 0 || plain.n() == 0 ? "-" : ratio(captured.medianMs(), plain.medianMs()))
                            .append(" |\n");
                }
            }
        }
        sb.append("\n- 성공 표본이 0회면 `-`. 조합마다 ").append(RUNS).append("회 중 성공한 회차만 통계에 넣는다\n");
        sb.append("- 락 보유 시간은 안전장치가 하는 일 전부를 포함한다: 사본 조회, 변경 문장, 변경 뒤 키 재조회, 커밋. "
                + "UNCAPTURED는 변경 문장과 커밋뿐이다\n\n");

        sb.append("## 표 2: 동시 작성자(k=").append(CONCURRENT_K).append(", 락 대기 상한 ")
                .append(PROBE_LOCK_WAIT_SECONDS).append("초, 탐침마다 ").append(PROBE_REPEATS).append("회)\n\n");
        sb.append("| 기종 | 조건 인덱스 | 방식 | 탐침 | 결과 | 대기(ms) | 기준선(ms) | 잔여 락 보유(ms) | 판정 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Env e : envs) {
            for (String variant : VARIANTS) {
                for (Kind kind : KINDS) {
                    for (Probe probe : PROBES) {
                        Agg a = agg(probes, e.dbms(), variant, kind.name(), probe.name());
                        sb.append("| ").append(e.dbms()).append(" | ").append(variant).append(" | ").append(kind).append(" | ")
                                .append(probe.name()).append(" | ").append(a.outcomes()).append(" | ").append(fmt(a.waitMs()))
                                .append(" | ").append(fmt(a.baselineMs())).append(" | ").append(fmt(a.remainingHoldMs()))
                                .append(" | ").append(verdict(a)).append(" |\n");
                    }
                }
            }
        }
        sb.append("\n- 탐침: `update_in_range` = 대상 안 행(id=").append(CONCURRENT_K).append(") UPDATE, "
                + "`update_out_of_range` = 대상 밖 행(id=").append(ROWS).append(") UPDATE, "
                + "`insert_in_range` = 대상 조건 범위 안(id=").append(PROBE_INSERT_ID)
                .append(", amount=5) 새 행 INSERT. 셋 다 `note`만 바꾼다(조건 열 amount를 건드리지 않아 대상 행 집합이 변하지 않는다)\n");
        sb.append("- `기준선`은 같은 탐침 문장을 변경 없이 단독 실행한 시간(같은 조합에서 ").append(PROBE_REPEATS)
                .append("회, 중앙값)이다. 대기가 락 때문인지 문장 자체가 느린 것인지 가르는 대조군이다\n");
        sb.append("- `잔여 락 보유` = 변경의 커밋 완료 − 탐침 출발 시각. 탐침이 락에 막혔다면 대기가 이 값에 수렴한다\n");
        sb.append("- `판정` 규칙: 출발이 변경 커밋 뒤인 회차는 뺀다. 남은 회차의 중앙값이 (기준선 x 2 + 1ms) 이하면 "
                + "`안 막힘`. 그보다 길고 잔여 락 보유의 절반 이상을 기다렸으면 `막힘(커밋까지)`, 길지만 절반에 못 미치면 "
                + "`단정 보류(짧은 대기)`. 대기 상한을 넘긴 회차가 있으면 `막힘(타임아웃)`. 두 조건을 모두 요구하는 이유는 "
                + "UNCAPTURED처럼 잔여 락 보유가 1~2ms인 조합에서 막힘과 문장 자체의 지연을 가를 수 없기 때문이다\n");
        sb.append("- 두 번째 커넥션은 변경 쪽이 잠그는 첫 문장을 끝낸 신호(CountDownLatch) 뒤에 출발한다 — sleep으로 순서를 맞추지 않는다\n\n");

        sb.append("## 실행계획(참고)\n\n");
        sb.append("| 기종 | 조건 인덱스 | 단계 | 계획 |\n|---|---|---|---|\n");
        for (PlanRow p : planRows) {
            sb.append("| ").append(p.dbms()).append(" | ").append(p.variant()).append(" | ").append(p.stage())
                    .append(" | `").append(cut(p.plan().replace("|", "\\|"), 220)).append("` |\n");
        }

        sb.append("\n## 관찰 (숫자가 보여 주는 것)\n\n");
        sb.append("### 안전장치가 락을 얼마나 오래 잡는가\n\n");
        for (Env e : envs) {
            for (String variant : VARIANTS) {
                for (int k : K_VALUES) {
                    Stat captured = stat(lockRuns, e.dbms(), variant, Kind.UPDATE, k);
                    Stat plain = stat(lockRuns, e.dbms(), variant, Kind.UNCAPTURED, k);
                    sb.append("- ").append(e.dbms()).append(" / ").append(variant).append(" / k=").append(k).append(": ");
                    if (captured.n() == 0 || plain.n() == 0) {
                        sb.append("측정 실패(CAPTURED 성공 ").append(captured.n()).append("회, UNCAPTURED 성공 ")
                                .append(plain.n()).append("회)\n");
                        continue;
                    }
                    sb.append(String.format(Locale.ROOT, "CAPTURED 중앙값 %.2fms, UNCAPTURED 중앙값 %.2fms → 차이 %+.2fms(%s)",
                            captured.medianMs(), plain.medianMs(), captured.medianMs() - plain.medianMs(),
                            ratio(captured.medianMs(), plain.medianMs())));
                    sb.append('\n');
                }
            }
        }

        sb.append("\n### 동시 작성자\n\n");
        for (Env e : envs) {
            for (String variant : VARIANTS) {
                for (Kind kind : KINDS) {
                    for (Probe probe : PROBES) {
                        Agg a = agg(probes, e.dbms(), variant, kind.name(), probe.name());
                        sb.append("- ").append(e.dbms()).append(" / ").append(variant).append(" / ").append(kind)
                                .append(" / ").append(probe.name()).append(": ").append(a.outcomes())
                                .append(String.format(Locale.ROOT, ", 대기 중앙값 %s, 기준선 %s, 잔여 락 보유 %s → %s",
                                        fmt(a.waitMs()), fmt(a.baselineMs()), fmt(a.remainingHoldMs()), verdict(a)));
                        if (!a.detail().isEmpty()) {
                            sb.append(" (").append(a.detail()).append(')');
                        }
                        sb.append('\n');
                    }
                }
            }
        }

        sb.append("\n### MySQL 간격 잠금 가설\n\n");
        for (Env e : envs) {
            for (String variant : VARIANTS) {
                for (Kind kind : KINDS) {
                    Agg a = agg(probes, e.dbms(), variant, kind.name(), "insert_in_range");
                    sb.append("- ").append(e.dbms()).append(" / ").append(variant).append(" / ").append(kind)
                            .append(": 대상 범위 안 INSERT는 ");
                    if (a.n() == 0) {
                        sb.append("무효 표본뿐이라 판정할 수 없다\n");
                    } else if ("막힘(타임아웃)".equals(verdict(a))) {
                        sb.append("대기 상한을 넘겼다 → 간격 잠금이 관측됐다\n");
                    } else if ("막힘(커밋까지)".equals(verdict(a))) {
                        sb.append("변경이 커밋될 때까지 막혔다 → 간격 잠금이 관측됐다\n");
                    } else if ("안 막힘".equals(verdict(a))) {
                        sb.append("막히지 않았다 → 간격 잠금이 관측되지 않았다\n");
                    } else {
                        sb.append("기준선보다 조금 길게 기다렸다 → 간격 잠금으로 단정할 수 없다\n");
                    }
                }
            }
        }

        sb.append("\n### 조건 인덱스가 대상 밖 행에 미치는 영향\n\n");
        for (Env e : envs) {
            for (String variant : VARIANTS) {
                for (Kind kind : KINDS) {
                    Agg a = agg(probes, e.dbms(), variant, kind.name(), "update_out_of_range");
                    sb.append("- ").append(e.dbms()).append(" / ").append(variant).append(" / ").append(kind)
                            .append(": 대상 밖 행 UPDATE는 ").append(a.outcomes())
                            .append(String.format(Locale.ROOT, " (대기 중앙값 %s, 기준선 %s) → %s", fmt(a.waitMs()),
                                    fmt(a.baselineMs()), verdict(a)))
                            .append('\n');
                }
            }
        }

        sb.append("\n## 한계\n\n");
        sb.append("- 로컬 docker 컨테이너, 같은 호스트의 단일 클라이언트다 — 네트워크 지연이 없다. 실서비스의 대기 시간은 "
                + "네트워크 왕복만큼 더 길다\n");
        sb.append("- 락 보유 시간은 JDBC 프록시가 잰 값이라 드라이버가 문장을 보내기 전 준비 시간이 시작점에 포함된다. "
                + "커밋 완료 시각도 클라이언트가 커밋 응답을 받은 시각이다(서버 기준 커밋 시각이 아니다)\n");
        sb.append("- 락 보유 시간에는 안전장치의 모든 단계가 들어 있다. PostgreSQL의 큰 값은 사본 조회뿐 아니라 변경 뒤 "
                + "키 재조회(JdbcChangeRunner.selectByKeys, 키 100개를 OR로 묶음)의 비용까지 포함한 결과다 — 그 문장의 "
                + "계획도 위 실행계획 표에 함께 실었다\n");
        sb.append("- 조건 인덱스는 조건 열(amount)에 건다. 실험 계획서의 '인덱스 있음(grp에 인덱스)'을 그대로 쓰면 "
                + "k=10·100을 만들 수 없다 — grp는 0~99 균등이라 어떤 범위 조건도 200의 배수 행만 잡는다. 계획서가 허용한 "
                + "'인덱스 없는 열 amount로 범위 조건' 쪽을 양쪽 조합에 같은 모양으로 적용했다\n");
        sb.append("- 인덱스 있음 조합을 전부 끝낸 뒤 인덱스 없음 조합을 재는 순서라, 버퍼 캐시가 데워진 상태에서 두 번째를 잰다\n");
        sb.append("- 동시 작성자 측정은 조합마다 탐침 ").append(PROBE_REPEATS).append("회뿐이다. UNCAPTURED는 잠그는 문장이 "
                + "끝난 뒤 커밋까지의 창이 짧아, 출발이 커밋 뒤로 밀린 무효 표본이 섞인다 — 무효는 표의 결과와 CSV에 그대로 남겼다\n");
        sb.append("- 대상 밖 행 UPDATE 탐침의 기준선에는 커밋(디스크 fsync) 비용이 들어 있다. 이 실험의 대기 값은 "
                + "'막혔다/안 막혔다'를 가르는 비교용이지 절대 지연이 아니다\n");
        sb.append("- 사본 캡처·재조회가 쓰는 CPU·IO 비용은 여기서 따로 재지 않았다. 락 보유 시간만 잰다\n");
        sb.append("- 실험 테이블은 실측 후 삭제된다(`@AfterAll`). sample의 기존 테이블은 건드리지 않는다\n");
        return sb.toString();
    }

    /** 탐침 결과 집계 — 유효 회차(출발이 커밋 전)만 통계에 넣는다. */
    private static Agg agg(List<ProbeSample> probes, String dbms, String variant, String kind, String probe) {
        List<Double> waits = new ArrayList<>();
        List<Double> holds = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        int invalid = 0;
        for (ProbeSample p : probes) {
            if (!"concurrent".equals(p.section()) || !p.dbms().equals(dbms) || !p.variant().equals(variant)
                    || !p.kind().equals(kind) || !p.probe().equals(probe)) {
                continue;
            }
            outcomes.add(p.outcome());
            if (!Boolean.TRUE.equals(p.departedBeforeCommit())) {
                invalid++;
                continue;
            }
            if (p.waitMs() != null) {
                waits.add(p.waitMs());
            }
            if (p.remainingHoldMs() != null) {
                holds.add(p.remainingHoldMs());
            }
            if (p.detail() != null && !p.detail().isEmpty() && detail.indexOf(p.detail()) < 0) {
                detail.append(detail.length() > 0 ? " | " : "").append(p.detail());
            }
        }
        List<Double> baselines = probes.stream()
                .filter(p -> "probe_baseline".equals(p.section()) && p.dbms().equals(dbms) && p.variant().equals(variant)
                        && p.probe().equals(probe) && p.waitMs() != null)
                .map(ProbeSample::waitMs).toList();
        String summary = outcomes.isEmpty() ? "표본 없음" : String.join(", ", outcomes);
        if (invalid > 0) {
            summary += " (무효 " + invalid + "회 제외)";
        }
        return new Agg(summary, median(waits), median(baselines), median(holds), waits.size(), detail.toString());
    }

    /**
     * 판정은 두 조건을 모두 만족할 때만 "막힘"이다: (1) 대기가 기준선보다 확실히 길다(기준선 x 2 + 1ms 초과)
     * (2) 변경이 출발 뒤에도 잡고 있던 락 시간의 절반 이상을 기다렸다. 한 조건만 만족하면 단정하지 않는다 —
     * UNCAPTURED처럼 잔여 락 보유가 1~2ms인 조합에서는 막힘과 문장 자체의 지연을 구분할 수 없다.
     */
    private static String verdict(Agg a) {
        if (a.n() == 0 || a.waitMs() == null) {
            return "판정 불가";
        }
        if (a.outcomes().contains("LOCK_TIMEOUT")) {
            return "막힘(타임아웃)";
        }
        if (a.baselineMs() == null || a.remainingHoldMs() == null) {
            return "판정 불가";
        }
        if (a.waitMs() <= a.baselineMs() * 2 + 1.0) {
            return "안 막힘";
        }
        return a.waitMs() >= a.remainingHoldMs() / 2 ? "막힘(커밋까지)" : "단정 보류(짧은 대기)";
    }

    private static Double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.isEmpty() ? null : sorted.get(sorted.size() / 2);
    }

    private static String ratio(double captured, double plain) {
        return plain <= 0 ? "-" : String.format(Locale.ROOT, "%.1f배", captured / plain);
    }

    /** 실행계획 원문은 100개 OR 조건을 그대로 담아 수 KB가 되기도 한다 — 표가 읽히게 자른다. */
    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + " …";
    }

    private static String fmt(Double v) {
        return v == null || v.isNaN() ? "-" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String num(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static Stat stat(List<LockRun> runs, String dbms, String variant, Kind kind, int k) {
        List<Double> values = runs.stream()
                .filter(r -> r.dbms().equals(dbms) && r.variant().equals(variant) && r.kind().equals(kind.name())
                        && r.k() == k && "measured".equals(r.phase()) && r.lockHoldMs() != null)
                .map(LockRun::lockHoldMs).sorted().toList();
        if (values.isEmpty()) {
            return new Stat(0, Double.NaN, Double.NaN);
        }
        double median = values.get(values.size() / 2);
        double p95 = values.get((int) Math.ceil(0.95 * values.size()) - 1);
        return new Stat(values.size(), median, p95);
    }

    // ---------------------------------------------------------------- 자료 구조

    private record Db(String dbms, AbstractJdbcOperator op, ConsoleCredential cred) {
        boolean mysql() {
            return "MySQL".equals(dbms);
        }

        String url() {
            return op.jdbcUrl();
        }

        JdbcChangeRunner runner() {
            return ReflectionTestUtils.invokeMethod(op, "changeRunner");
        }
    }

    private record Env(String dbms, String version, String isolation, long rows) {
    }

    private record Attempt(ChangeOutcome outcome, String error) {
        boolean ok() {
            return outcome != null && outcome.committed();
        }
    }

    private record Probe(String name, String sql) {
    }

    private record PlanRow(String dbms, String variant, String stage, String predicate, String plan) {
    }

    private record Stat(int n, double medianMs, double p95Ms) {
    }

    private record Agg(String outcomes, Double waitMs, Double baselineMs, Double remainingHoldMs, int n, String detail) {
    }

    private record LockRun(String dbms, String variant, String kind, int k, String phase, int run,
                           Double lockHoldMs, Double statementMs, Double commitMs, Long affected, String error) {
    }

    private record ProbeSample(String section, String dbms, String variant, String kind, String probe, int repeat,
                               Double waitMs, Double baselineMs, Double remainingHoldMs, String outcome,
                               String detail, Boolean departedBeforeCommit, String note) {
    }
}
