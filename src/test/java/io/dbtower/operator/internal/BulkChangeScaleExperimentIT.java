package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.model.BulkBatchOutcome;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.operator.model.ChangeOutcome;
import io.dbtower.operator.model.ChangePlan;
import io.dbtower.operator.model.ChangePlan.Kind;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E6 — 대량 일괄 변경을 100만 행에서 실측한다(#105).
 *
 * <p>답하려는 질문은 하나다. <b>배치로 쪼갠 실행이 같은 일을 하는 한 트랜잭션보다 남의 쓰기를 덜 미는가.</b>
 * E3(change-lock-modes)는 10,000행까지만 쟀고, 그 표가 배치 크기 1,000행·쉼 100ms라는 기본값의 근거였다.
 * 여기서는 그 기본값으로 100만 행을 실제로 돌려 본다.
 *
 * <p>비교 대상은 행 사본 방식이 아니다. 100만 행을 한 트랜잭션에 넣는 것은 제품이 허용하지 않고
 * (상한 10,000행), 사본을 100만 행 뜨는 것은 되돌리기 보장이 아니라 또 하나의 사본 관리 문제다.
 * 그래서 <b>같은 UPDATE를 조건 그대로 한 번에 실행</b>하는 것과 비교한다 — 대량 변경을 플랫폼 밖에서
 * 손으로 돌릴 때 실제로 하는 일이 그것이다.
 *
 * <p>동시 작성자는 E3와 같은 모양으로 둔다: 범위 안 임의 행을 쉬지 않고 고쳐 커밋하며, 각 커밋의 소요를 잰다.
 * 여기서 보는 수치가 "이 변경이 도는 동안 남들이 얼마나 기다렸나"다.
 *
 * <p>실행: {@code DBTOWER_EXPERIMENT=1 ./gradlew test --tests '*BulkChangeScaleExperimentIT'}
 * 대상은 docker compose의 MySQL 13306·PostgreSQL 15432. 100만 행 적재 때문에 한 기종당 몇 분 걸린다.
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_EXPERIMENT", matches = "1")
class BulkChangeScaleExperimentIT {

    private static final Path CSV_PATH = Path.of("docs", "experiments", "bulk-change-scale.csv");
    private static final Path DOC_PATH = Path.of("docs", "experiments", "bulk-change-scale.md");

    private static final int ROWS = 1_000_000;
    private static final int BATCH_ROWS = 1_000;
    private static final long PAUSE_MILLIS = 100;
    private static final int TIMEOUT_SECONDS = 120;
    private static final int WRITERS = 2;

    private static final String MYSQL_URL = "jdbc:mysql://127.0.0.1:13306/sample?rewriteBatchedStatements=true";
    private static final String PG_URL = "jdbc:postgresql://127.0.0.1:15432/sample";
    private static final ConsoleCredential MYSQL_CRED = new ConsoleCredential("root", "dbtower1234");
    private static final ConsoleCredential PG_CRED = new ConsoleCredential("postgres", "dbtower1234");

    private static final ConnectionPools POOLS = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    private static final List<Run> RUNS = new ArrayList<>();

    @AfterAll
    static void closePools() {
        POOLS.closeAll();
    }

    /** 한 번의 측정 — 방식 하나를 한 기종에서 돌린 결과 */
    private record Run(String dbms, String mode, long totalMillis, long affectedRows, int batches,
                       double writerMedianMs, double writerP95Ms, double writerMaxMs, long writerCommits) {
    }

    private record Db(String name, DbmsType type, String url, ConsoleCredential cred, int port) {
    }

    private static final List<Db> DBS = List.of(
            new Db("MySQL", DbmsType.MYSQL, MYSQL_URL, MYSQL_CRED, 13306),
            new Db("PostgreSQL", DbmsType.POSTGRESQL, PG_URL, PG_CRED, 15432));

    @Test
    void 배치_실행과_한_번에_실행을_100만_행에서_잰다() throws Exception {
        String startedAt = OffsetDateTime.now().toString();
        List<String> envs = new ArrayList<>();
        for (Db db : DBS) {
            envs.add(db.name() + " " + version(db));
            for (String mode : List.of("BATCH", "SINGLE")) {
                seed(db);
                RUNS.add(measure(db, mode));
                System.out.println("[E6] " + db.name() + "/" + mode + " " + RUNS.getLast());
            }
            drop(db);
        }
        Files.createDirectories(CSV_PATH.getParent());
        Files.writeString(CSV_PATH, toCsv(), StandardCharsets.UTF_8);
        Files.writeString(DOC_PATH, toMarkdown(envs, startedAt), StandardCharsets.UTF_8);
        System.out.println("[E6] 결과 기록: " + DOC_PATH.toAbsolutePath());

        assertEquals(DBS.size() * 2, RUNS.size(), "기종 2 × 방식 2 = 4회를 모두 쟀어야 한다");
        for (Run r : RUNS) {
            assertEquals(ROWS, r.affectedRows(), r.dbms() + "/" + r.mode() + " 는 100만 행을 모두 바꿔야 한다");
        }
    }

    /**
     * 방식 하나를 잰다. 동시 작성자를 먼저 띄워 두고 변경을 돌린 뒤, 변경이 끝나면 작성자를 멈춘다 —
     * 변경이 도는 동안의 커밋만 표본에 들어간다.
     */
    private Run measure(Db db, String mode) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(WRITERS);
        List<Long> writerMicros = Collections.synchronizedList(new ArrayList<>());
        List<Thread> writers = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            Thread t = Thread.ofPlatform().name("e6-writer-" + i).start(() -> writer(db, stop, ready, writerMicros));
            writers.add(t);
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "동시 작성자가 준비되지 않았다");

        long t0 = System.nanoTime();
        long affected;
        int batches = 0;
        if ("BATCH".equals(mode)) {
            AbstractJdbcOperator op = operator(db);
            BulkChangePlan plan = new BulkChangePlan("UPDATE bulk_scale SET note = 'done'", "kind = 'M'",
                    "bulk_scale", "id", BATCH_ROWS, TIMEOUT_SECONDS);
            long total = 0;
            Object lastKey = null;
            while (true) {
                Object toKey = op.nextBulkBoundary(db.cred(), plan, lastKey);
                if (toKey == null) {
                    break;
                }
                BulkBatchOutcome outcome = op.executeBulkBatch(db.cred(), plan, lastKey, toKey);
                total += outcome.affectedRows();
                lastKey = toKey;
                batches++;
                Thread.sleep(PAUSE_MILLIS);
            }
            affected = total;
        } else {
            affected = single(db);
        }
        long totalMillis = (System.nanoTime() - t0) / 1_000_000;

        stop.set(true);
        for (Thread t : writers) {
            t.join(30_000);
        }
        List<Long> sorted = new ArrayList<>(writerMicros);
        Collections.sort(sorted);
        return new Run(db.name(), mode, totalMillis, affected, batches,
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 1.0), sorted.size());
    }

    /**
     * 같은 변경을 한 문장으로. 제품의 소량 경로(행 사본·상한 10,000행)가 아니라, 대량 변경을 플랫폼 밖에서
     * 손으로 돌릴 때 실제로 하는 일을 흉내 낸다.
     */
    private long single(Db db) throws SQLException {
        try (Connection c = DriverManager.getConnection(db.url(), db.cred().username(), db.cred().password());
             PreparedStatement ps = c.prepareStatement("UPDATE bulk_scale SET note = 'done' WHERE kind = 'M'")) {
            ps.setQueryTimeout(TIMEOUT_SECONDS);
            return ps.executeLargeUpdate();
        }
    }

    /** 범위 안 임의 행을 쉬지 않고 고쳐 커밋한다. 각 커밋의 소요가 "남들이 얼마나 기다렸나"다. */
    private void writer(Db db, AtomicBoolean stop, CountDownLatch ready, List<Long> micros) {
        try (Connection c = DriverManager.getConnection(db.url(), db.cred().username(), db.cred().password())) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement("UPDATE bulk_scale SET ver = ver + 1 WHERE id = ?")) {
                ps.setQueryTimeout(TIMEOUT_SECONDS);
                ready.countDown();
                java.util.Random random = new java.util.Random(20260918L);
                while (!stop.get()) {
                    ps.setLong(1, random.nextInt(ROWS) + 1L);
                    long t0 = System.nanoTime();
                    ps.executeUpdate();
                    micros.add((System.nanoTime() - t0) / 1000);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("동시 작성자 실패: " + e.getMessage(), e);
        }
    }

    private AbstractJdbcOperator operator(Db db) {
        DatabaseInstance instance = new DatabaseInstance("e6-" + db.name(), db.type(), "127.0.0.1", db.port(),
                "sample", "unused", "unused");
        ReflectionTestUtils.setField(instance, "id", db.type() == DbmsType.MYSQL ? 9401L : 9402L);
        return db.type() == DbmsType.MYSQL
                ? new MySqlOperator(instance, POOLS, null, null)
                : new PostgresOperator(instance, POOLS, null);
    }

    /** 키는 연속으로 둔다 — 여기서 재는 것은 경계 정확성이 아니라 비용이다(정확성은 BulkChangeBatchIT). */
    private void seed(Db db) throws SQLException {
        drop(db);
        exec(db, "CREATE TABLE bulk_scale (id BIGINT PRIMARY KEY, kind VARCHAR(4), ver INT, note VARCHAR(20))",
                "CREATE INDEX bulk_scale_kind_idx ON bulk_scale (kind)");
        try (Connection c = DriverManager.getConnection(db.url(), db.cred().username(), db.cred().password())) {
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
        }
        exec(db, db.type() == DbmsType.MYSQL ? "ANALYZE TABLE bulk_scale" : "ANALYZE bulk_scale");
        System.out.println("[E6] " + db.name() + " bulk_scale " + ROWS + "행 준비 완료");
    }

    private void drop(Db db) throws SQLException {
        exec(db, "DROP TABLE IF EXISTS bulk_scale");
    }

    private void exec(Db db, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(db.url(), db.cred().username(), db.cred().password());
             Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }

    private String version(Db db) throws SQLException {
        try (Connection c = DriverManager.getConnection(db.url(), db.cred().username(), db.cred().password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version()")) {
            return rs.next() ? rs.getString(1).split("\n")[0] : "?";
        }
    }

    /** 최근접 순위 — 표본이 많아(수만 건) 보간 없이도 충분하다 */
    private static double percentile(List<Long> sortedMicros, double p) {
        if (sortedMicros.isEmpty()) {
            return Double.NaN;
        }
        int idx = (int) Math.ceil(p * sortedMicros.size()) - 1;
        return sortedMicros.get(Math.max(0, Math.min(idx, sortedMicros.size() - 1))) / 1000.0;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String toCsv() {
        StringBuilder sb = new StringBuilder("dbms,mode,total_ms,affected_rows,batches,"
                + "writer_median_ms,writer_p95_ms,writer_max_ms,writer_commits\n");
        for (Run r : RUNS) {
            sb.append(r.dbms()).append(',').append(r.mode()).append(',').append(r.totalMillis()).append(',')
                    .append(r.affectedRows()).append(',').append(r.batches()).append(',')
                    .append(fmt(r.writerMedianMs())).append(',').append(fmt(r.writerP95Ms())).append(',')
                    .append(fmt(r.writerMaxMs())).append(',').append(r.writerCommits()).append('\n');
        }
        return sb.toString();
    }

    private static String toMarkdown(List<String> envs, String startedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("# E6: 대량 일괄 변경을 100만 행에서 잰다\n\n");
        sb.append("이 문서와 `bulk-change-scale.csv`는 `BulkChangeScaleExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.\n\n");
        sb.append("답하려는 질문은 하나다. **배치로 쪼갠 실행이 같은 일을 하는 한 트랜잭션보다 남의 쓰기를 덜 미는가.**\n\n");
        sb.append("- 실행 일시: ").append(startedAt).append('\n');
        for (String env : envs) {
            sb.append("- ").append(env).append('\n');
        }
        sb.append("- 대상: `bulk_scale` ").append(ROWS).append("행, 변경 `UPDATE bulk_scale SET note = 'done' WHERE kind = 'M'`")
                .append(" (전 행이 조건에 걸린다)\n");
        sb.append("- BATCH: 기본 키 범위 배치 ").append(BATCH_ROWS).append("행 · 배치 사이 쉼 ").append(PAUSE_MILLIS)
                .append("ms · 구간마다 커밋\n");
        sb.append("- SINGLE: 같은 변경을 한 문장으로. 제품의 소량 경로(행 사본·상한 10,000행)가 아니라, ")
                .append("대량 변경을 플랫폼 밖에서 손으로 돌릴 때 하는 일이다\n");
        sb.append("- 동시 작성자 ").append(WRITERS).append("개가 범위 안 임의 행을 쉬지 않고 고쳐 커밋한다. ")
                .append("표의 작성자 수치는 그 커밋들의 소요다 — 이 변경이 도는 동안 남들이 얼마나 기다렸나\n\n");

        sb.append("## 결과\n\n");
        sb.append("| 기종 | 방식 | 총 소요 | 바뀐 행 | 배치 수 | 작성자 중앙값 | 작성자 p95 | 작성자 최대 | 작성자 커밋 수 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Run r : RUNS) {
            sb.append("| ").append(r.dbms()).append(" | ").append(r.mode()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f초", r.totalMillis() / 1000.0)).append(" | ")
                    .append(r.affectedRows()).append(" | ").append(r.batches() == 0 ? "-" : r.batches()).append(" | ")
                    .append(fmt(r.writerMedianMs())).append("ms | ").append(fmt(r.writerP95Ms())).append("ms | ")
                    .append(fmt(r.writerMaxMs())).append("ms | ").append(r.writerCommits()).append(" |\n");
        }

        sb.append("\n## 읽는 법\n\n");
        sb.append("- **작성자 p95·최대**가 이 실험의 답이다. 배치가 총 소요를 늘리는 대신(쉼 ")
                .append(PAUSE_MILLIS).append("ms × 배치 수) 남의 쓰기를 얼마나 덜 미는지를 본다\n");
        sb.append("- **작성자 커밋 수**는 같은 시간 동안 남들이 얼마나 일했는지다. 총 소요가 길어도 커밋 수가 비례해 늘면 남들은 막히지 않은 것이다\n");
        sb.append("- 총 소요를 그대로 비교하면 안 된다 — 배치는 일부러 쉬고, 그 쉼이 설계다\n\n");

        sb.append("## 한계\n\n");
        sb.append("- 한 회씩만 쟀다. E3(change-lock-modes)처럼 워밍업 뒤 7회 반복하지 않는다 — 100만 행 적재가 회당 몇 분이라 반복 비용이 크다\n");
        sb.append("- 복제가 없는 단독 인스턴스다. 복제 지연 스로틀이 실제로 언제 멈추는지는 여기서 재지 않는다\n");
        sb.append("- 조건이 전 행에 걸려 경계 조회가 인덱스를 타고 순서대로 나아간다. 조건에 맞는 행이 드문드문한 테이블은 경계 조회가 더 든다\n");
        sb.append("- 같은 호스트의 docker 컨테이너다. 네트워크 왕복이 거의 없어 배치 수가 많아질수록 실제 환경보다 유리하다\n");
        return sb.toString();
    }
}
