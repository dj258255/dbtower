package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.model.ChangeOutcome;
import io.dbtower.operator.model.ChangePlan;
import io.dbtower.operator.model.ChangePlan.Kind;
import io.dbtower.operator.model.RevertPlan;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 승인 티켓 실행 계층을 실제 DB에서 증명한다. 분류기도 서비스도 빼고 오퍼레이터만으로:
 * (1) 드라이런은 흔적을 남기지 않는다, (2) 실행 뒤 되돌리기는 원래 행을 복원한다(따옴표·백슬래시·주입 모양 문자열·시간 소수부·이진값 포함),
 * (3) 실행 뒤 누가 바꾼 행이 하나라도 있으면 되돌리기는 아무것도 쓰지 않는다, (4) 사본 조회가 실제 대상과 어긋나면 커밋하지 않는다,
 * (5) 사본 상한을 넘으면 실행하지 않는다, (6) DDL이 암묵 커밋인 기종은 드라이런을 거부하고, 트랜잭션 DDL 기종은 커밋 전 인덱스가
 * 같은 트랜잭션의 실행계획에 보인다.
 *
 * <p>docker compose의 대상 DB가 필요하다: {@code DBTOWER_CONSOLE_IT=1 ./gradlew test --tests '*ChangeExecutionIT'}
 */
class ChangeExecutionIT {

    private static final String GATE = "DBTOWER_CONSOLE_IT";
    private static final String MSSQL_GATE = "DBTOWER_MSSQL_IT";
    private static final String TRICKY = "O'Brien \\ \"q\" ; DROP TABLE x; --";

    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    @AfterEach
    void close() {
        pools.closeAll();
    }

    private interface Work {
        void run(Connection c) throws SQLException;
    }

    private static DatabaseInstance instance(long id, DbmsType type, int port, String dbName) {
        DatabaseInstance instance = new DatabaseInstance("change-it-" + id, type, "127.0.0.1", port, dbName, "unused", "unused");
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    private static void with(String url, ConsoleCredential cred, Work work) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, cred.username(), cred.password())) {
            work.run(c);
        }
    }

    private static void exec(String url, ConsoleCredential cred, String... statements) throws SQLException {
        with(url, cred, c -> {
            try (Statement st = c.createStatement()) {
                for (String sql : statements) {
                    try {
                        st.execute(sql);
                    } catch (SQLException e) {
                        // 재실행의 "이미 있음"(Oracle ORA-00955)은 무시한다
                        if (!String.valueOf(e.getMessage()).contains("ORA-00955")) {
                            throw e;
                        }
                    }
                }
            }
        });
    }

    /** 대상 DB가 직접 찍은 문자열로 비교한다 — 사본 부호화와 무관한 기준 */
    private static List<List<String>> rows(String url, ConsoleCredential cred, String sql) throws SQLException {
        List<List<String>> out = new ArrayList<>();
        with(url, cred, c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        row.add(rs.getString(i));
                    }
                    out.add(row);
                }
            }
        });
        return out;
    }

    /** where가 있으면 사본 조회는 table + where, 락 문법은 기종 오퍼레이터가 조립한다 */
    private static ChangePlan plan(Kind kind, String sql, String table, String where, int maxRows, boolean dryRun) {
        return new ChangePlan(kind, sql, table, where == null ? null : table, where, null, maxRows, 10, dryRun);
    }

    private static boolean ddlCommits(String url, ConsoleCredential cred) throws SQLException {
        boolean[] result = new boolean[1];
        with(url, cred, c -> result[0] = c.getMetaData().dataDefinitionCausesTransactionCommit());
        return result[0];
    }

    private void scenario(AbstractJdbcOperator op, ConsoleCredential cred, String table, Work seed, String insertSql)
            throws SQLException {
        String url = op.jdbcUrl();
        String all = "SELECT * FROM " + table + " ORDER BY id";
        with(url, cred, seed);
        List<List<String>> original = rows(url, cred, all);
        assertEquals(3, original.size());
        String update = "UPDATE " + table + " SET name = 'changed' WHERE id IN (1, 2)";
        String capture = "WHERE id IN (1, 2)";
        String type = op.instance.getType().name();

        ChangeOutcome dry = op.executeChange(cred, plan(Kind.UPDATE, update, table, capture, 100, true));
        System.out.println("[" + type + " 드라이런] committed=" + dry.committed() + " affected=" + dry.affectedRows()
                + " keys=" + dry.before().keyColumns() + " unavailable=" + dry.rollbackUnavailable());
        assertFalse(dry.committed());
        assertEquals(2, dry.affectedRows());
        assertNull(dry.rollbackUnavailable());
        assertEquals("changed", dry.after().rows().get(0).get(dry.after().indexOf("name")));
        assertEquals(original, rows(url, cred, all), "드라이런은 흔적을 남기지 않는다");

        ChangeOutcome done = op.executeChange(cred, plan(Kind.UPDATE, update, table, capture, 100, false));
        assertTrue(done.committed());
        assertNotEquals(original, rows(url, cred, all));
        RevertPlan.Outcome revertDry = op.revertChange(cred, new RevertPlan(Kind.UPDATE, table, done.before(), done.after(), 10, true));
        assertEquals(2, revertDry.restoredRows());
        assertNotEquals(original, rows(url, cred, all), "되돌리기 드라이런도 흔적을 남기지 않는다");
        RevertPlan.Outcome reverted = op.revertChange(cred, new RevertPlan(Kind.UPDATE, table, done.before(), done.after(), 10, false));
        System.out.println("[" + type + " UPDATE 되돌리기] committed=" + reverted.committed() + " restored=" + reverted.restoredRows());
        assertTrue(reverted.committed());
        assertEquals(original, rows(url, cred, all), "따옴표·백슬래시·시간 소수부·이진값까지 원래 행으로 복원한다");

        ChangeOutcome again = op.executeChange(cred, plan(Kind.UPDATE, update, table, capture, 100, false));
        exec(url, cred, "UPDATE " + table + " SET name = 'someone' WHERE id = 1");
        RevertPlan.Outcome conflict = op.revertChange(cred, new RevertPlan(Kind.UPDATE, table, again.before(), again.after(), 10, false));
        System.out.println("[" + type + " 드리프트] committed=" + conflict.committed() + " conflicts=" + conflict.conflicts());
        assertFalse(conflict.committed());
        assertEquals(1, conflict.conflicts().size());
        assertEquals("name", conflict.conflicts().get(0).changedColumns().get(0).toLowerCase());
        assertEquals("changed", rows(url, cred, "SELECT name FROM " + table + " WHERE id = 2").get(0).get(0),
                "충돌이 하나라도 있으면 충돌 없는 행도 되돌리지 않는다");
        with(url, cred, seed);

        ChangeOutcome deleted = op.executeChange(cred, plan(Kind.DELETE, "DELETE FROM " + table + " WHERE id = 3", table,
                "WHERE id = 3", 100, false));
        assertEquals(1, deleted.affectedRows());
        assertEquals(2, rows(url, cred, all).size());
        op.revertChange(cred, new RevertPlan(Kind.DELETE, table, deleted.before(), deleted.after(), 10, false));
        assertEquals(original, rows(url, cred, all), "삭제한 행을 같은 값으로 다시 넣는다");

        ChangeOutcome inserted = op.executeChange(cred, plan(Kind.INSERT, insertSql, table, null, 100, false));
        System.out.println("[" + type + " INSERT] affected=" + inserted.affectedRows() + " unavailable=" + inserted.rollbackUnavailable()
                + " afterRows=" + (inserted.after() == null ? null : inserted.after().rows().size()));
        assertNull(inserted.rollbackUnavailable(), "생성된 키로 넣은 행을 짚어야 한다");
        assertEquals(4, rows(url, cred, all).size());
        op.revertChange(cred, new RevertPlan(Kind.INSERT, table, inserted.before(), inserted.after(), 10, false));
        assertEquals(original, rows(url, cred, all));

        OperatorException mismatch = assertThrows(OperatorException.class, () -> op.executeChange(cred,
                plan(Kind.UPDATE, update, table, "WHERE id = 1", 100, false)));
        System.out.println("[" + type + " 사본 어긋남] " + mismatch.getMessage());
        assertTrue(mismatch.getMessage().contains("영향 행 수"), mismatch.getMessage());
        assertEquals(original, rows(url, cred, all), "사본과 실제 대상이 어긋나면 커밋하지 않는다");

        OperatorException cap = assertThrows(OperatorException.class,
                () -> op.executeChange(cred, plan(Kind.UPDATE, update, table, capture, 1, false)));
        assertTrue(cap.getMessage().contains("사본 상한"), cap.getMessage());
        assertEquals(original, rows(url, cred, all));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    void MySQL_실행_되돌리기_드리프트_불변식() throws Exception {
        MySqlOperator op = new MySqlOperator(instance(9201, DbmsType.MYSQL, 13306, "sample"), pools, null, null);
        ConsoleCredential root = new ConsoleCredential("root", "dbtower1234");
        exec(op.jdbcUrl(), root, "CREATE TABLE IF NOT EXISTS sample.change_it (id INT AUTO_INCREMENT PRIMARY KEY,"
                + " name VARCHAR(50) NOT NULL, note TEXT, amount DECIMAL(10,2), flag TINYINT(1), updated DATETIME(6), payload VARBINARY(16))");
        scenario(op, root, "change_it", c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM sample.change_it");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sample.change_it (id, name, note, amount, flag, updated, payload) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                insert(ps, 1, "Hong", TRICKY, new BigDecimal("10.50"), true, LocalDateTime.parse("2026-09-10T12:34:56.789012"), new byte[]{0, 1, (byte) 0xff});
                insert(ps, 2, "Kim", null, new BigDecimal("0.00"), false, LocalDateTime.parse("2026-09-10T00:00:00.000001"), null);
                insert(ps, 3, "Lee", "plain", new BigDecimal("-3.25"), null, null, new byte[]{7});
            }
        }, "INSERT INTO change_it (name, note, amount) VALUES ('new', 'n', 1.00)");

        assertTrue(ddlCommits(op.jdbcUrl(), root), "MySQL DDL은 암묵 커밋이다(드라이런 거부의 근거)");
        OperatorException ddl = assertThrows(OperatorException.class, () -> op.executeChange(root,
                plan(Kind.DDL, "CREATE INDEX change_it_name_idx ON sample.change_it (name)", null, null, 100, true)));
        System.out.println("[MySQL DDL 드라이런] " + ddl.getMessage());
        assertTrue(rows(op.jdbcUrl(), root, "SHOW INDEX FROM sample.change_it WHERE Key_name = 'change_it_name_idx'").isEmpty());
    }

    private static void insert(PreparedStatement ps, int id, String name, String note, BigDecimal amount, Boolean flag,
                               LocalDateTime updated, byte[] payload) throws SQLException {
        ps.setInt(1, id);
        ps.setString(2, name);
        ps.setString(3, note);
        ps.setBigDecimal(4, amount);
        if (flag == null) {
            ps.setNull(5, Types.TINYINT);
        } else {
            ps.setInt(5, flag ? 1 : 0);
        }
        ps.setObject(6, updated);
        ps.setBytes(7, payload);
        ps.executeUpdate();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    void PostgreSQL_실행_되돌리기_드리프트_불변식과_트랜잭션_안_실행계획() throws Exception {
        PostgresOperator op = new PostgresOperator(instance(9202, DbmsType.POSTGRESQL, 15432, "sample"), pools, null);
        ConsoleCredential postgres = new ConsoleCredential("postgres", "dbtower1234");
        exec(op.jdbcUrl(), postgres, "CREATE TABLE IF NOT EXISTS change_it (id SERIAL PRIMARY KEY, name VARCHAR(50) NOT NULL,"
                + " note TEXT, amount NUMERIC(10,2), flag BOOLEAN, updated TIMESTAMPTZ, payload BYTEA, tags JSONB)");
        scenario(op, postgres, "change_it", c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM change_it");
                st.execute("SELECT setval(pg_get_serial_sequence('change_it', 'id'), 100)");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO change_it (id, name, note, amount, flag, updated, payload, tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                pgInsert(ps, 1, "Hong", TRICKY, "10.50", true, "2026-09-10T12:34:56.789012+09:00", new byte[]{0, 1, (byte) 0xff}, "{\"a\": [1, 2]}");
                pgInsert(ps, 2, "Kim", null, "0.00", false, "2026-09-10T00:00:00.000001Z", null, null);
                pgInsert(ps, 3, "Lee", "plain", "-3.25", null, null, new byte[]{7}, "[]");
            }
        }, "INSERT INTO change_it (name, note, amount) VALUES ('new', 'n', 1.00)");

        assertFalse(ddlCommits(op.jdbcUrl(), postgres), "PostgreSQL DDL은 트랜잭션 안에 있다");
        exec(op.jdbcUrl(), postgres, "CREATE TABLE IF NOT EXISTS change_it_big (id SERIAL PRIMARY KEY, status VARCHAR(10))",
                "DROP INDEX IF EXISTS change_it_big_status_idx",
                "INSERT INTO change_it_big (status) SELECT 'S' || (n % 1000) FROM generate_series(1, 20000) n"
                        + " WHERE NOT EXISTS (SELECT 1 FROM change_it_big)",
                "ANALYZE change_it_big");
        ChangeOutcome ddl = op.executeChange(postgres, new ChangePlan(Kind.DDL,
                "CREATE INDEX change_it_big_status_idx ON change_it_big (status)", null, null, null,
                "SELECT id FROM change_it_big WHERE status = 'S7'", 100, 10, true));
        System.out.println("[PG DDL 드라이런 전 계획]\n" + ddl.probeBefore().plan() + "  timings(us)=" + ddl.probeBefore().timingsMicros());
        System.out.println("[PG DDL 드라이런 후 계획]\n" + ddl.probeAfter().plan() + "  timings(us)=" + ddl.probeAfter().timingsMicros());
        assertFalse(ddl.committed());
        assertTrue(ddl.probeBefore().plan().contains("Seq Scan"), ddl.probeBefore().plan());
        assertTrue(ddl.probeAfter().plan().contains("change_it_big_status_idx"), "커밋 전 인덱스가 같은 트랜잭션의 계획에 보인다");
        assertEquals("0", rows(op.jdbcUrl(), postgres,
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'change_it_big_status_idx'").get(0).get(0), "드라이런 뒤 인덱스는 없다");
    }

    private static void pgInsert(PreparedStatement ps, int id, String name, String note, String amount, Boolean flag,
                                 String updated, byte[] payload, String tags) throws SQLException {
        ps.setInt(1, id);
        ps.setString(2, name);
        ps.setString(3, note);
        ps.setBigDecimal(4, new BigDecimal(amount));
        if (flag == null) {
            ps.setNull(5, Types.BOOLEAN);
        } else {
            ps.setBoolean(5, flag);
        }
        ps.setObject(6, updated == null ? null : OffsetDateTime.parse(updated));
        ps.setBytes(7, payload);
        ps.setObject(8, tags, Types.OTHER);
        ps.executeUpdate();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    void Oracle_실행_되돌리기_드리프트_불변식() throws Exception {
        OracleOperator op = new OracleOperator(instance(9203, DbmsType.ORACLE, 11521, "FREEPDB1"), pools, null);
        ConsoleCredential owner = new ConsoleCredential("sample", "dbtower1234");
        exec(op.jdbcUrl(), owner, "CREATE TABLE change_it (id NUMBER GENERATED BY DEFAULT AS IDENTITY (START WITH 100) PRIMARY KEY,"
                + " name VARCHAR2(50) NOT NULL, note VARCHAR2(200), amount NUMBER(10,2), updated TIMESTAMP(6), payload RAW(16))");
        scenario(op, owner, "change_it", c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM change_it");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO change_it (id, name, note, amount, updated, payload) VALUES (?, ?, ?, ?, ?, ?)")) {
                oraInsert(ps, 1, "Hong", TRICKY, "10.50", LocalDateTime.parse("2026-09-10T12:34:56.789012"), new byte[]{0, 1, (byte) 0xff});
                oraInsert(ps, 2, "Kim", null, "0.00", LocalDateTime.parse("2026-09-10T00:00:00.000001"), null);
                oraInsert(ps, 3, "Lee", "plain", "-3.25", null, new byte[]{7});
            }
        }, "INSERT INTO change_it (name, note, amount) VALUES ('new', 'n', 1.00)");

        boolean commits = ddlCommits(op.jdbcUrl(), owner);
        System.out.println("[Oracle dataDefinitionCausesTransactionCommit] " + commits);
        assertTrue(commits, "Oracle DDL은 암묵 커밋이다(드라이런 거부의 근거)");
    }

    /**
     * SQL Server 계열 — 로컬(Apple Silicon, Rosetta 없음)에서는 arm64 Azure SQL Edge로 잰다(docker-compose.arm64.yml).
     * 다른 기종과 게이트를 나눈 이유: 기본 compose의 SQL Server 이미지는 이 환경에서 뜨지 않아 늘 켜 둘 수 없다.
     * {@code DBTOWER_MSSQL_IT=1 ./gradlew test --tests '*ChangeExecutionIT'}
     */
    @Test
    @EnabledIfEnvironmentVariable(named = MSSQL_GATE, matches = "1")
    void SQLServer계열_실행_되돌리기_드리프트_불변식과_트랜잭션_안_실행계획() throws Exception {
        MsSqlOperator op = new MsSqlOperator(instance(9204, DbmsType.MSSQL, 11433, "sample"), pools, null);
        ConsoleCredential sa = new ConsoleCredential("sa", "Dbtower1234!");
        exec(op.jdbcUrl(), sa, "IF OBJECT_ID('dbo.change_it') IS NULL CREATE TABLE dbo.change_it (id INT IDENTITY(100, 1) PRIMARY KEY,"
                + " name NVARCHAR(50) NOT NULL, note NVARCHAR(200), amount DECIMAL(10,2), flag BIT, updated DATETIME2(6), payload VARBINARY(16))");
        scenario(op, sa, "change_it", c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM dbo.change_it");
                st.execute("SET IDENTITY_INSERT dbo.change_it ON");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO dbo.change_it (id, name, note, amount, flag, updated, payload) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                msInsert(ps, 1, "Hong", TRICKY, "10.50", true, LocalDateTime.parse("2026-09-10T12:34:56.789012"), new byte[]{0, 1, (byte) 0xff});
                msInsert(ps, 2, "Kim", null, "0.00", false, LocalDateTime.parse("2026-09-10T00:00:00.000001"), null);
                msInsert(ps, 3, "Lee", "plain", "-3.25", null, null, new byte[]{7});
            }
            try (Statement st = c.createStatement()) {
                st.execute("SET IDENTITY_INSERT dbo.change_it OFF");
            }
        }, "INSERT INTO change_it (name, note, amount) VALUES ('new', 'n', 1.00)");

        String url = op.jdbcUrl();
        boolean commits = ddlCommits(url, sa);
        System.out.println("[MSSQL dataDefinitionCausesTransactionCommit] " + commits);
        assertFalse(commits, "SQL Server 계열 DDL은 트랜잭션 안에 있다");
        exec(url, sa, "IF OBJECT_ID('dbo.change_it_big') IS NULL CREATE TABLE dbo.change_it_big (id INT IDENTITY PRIMARY KEY, status NVARCHAR(10))",
                "IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'change_it_big_status_idx') DROP INDEX change_it_big_status_idx ON dbo.change_it_big",
                "IF NOT EXISTS (SELECT 1 FROM dbo.change_it_big) INSERT INTO dbo.change_it_big (status)"
                        + " SELECT TOP (20000) CONCAT('S', ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) % 1000)"
                        + " FROM sys.all_objects a CROSS JOIN sys.all_objects b",
                "UPDATE STATISTICS dbo.change_it_big");
        ChangeOutcome ddl = op.executeChange(sa, new ChangePlan(Kind.DDL,
                "CREATE INDEX change_it_big_status_idx ON dbo.change_it_big (status)", null, null, null,
                "SELECT id FROM dbo.change_it_big WHERE status = 'S7'", 100, 10, true));
        System.out.println("[MSSQL DDL 드라이런 전 계획]\n" + ddl.probeBefore().plan() + "  timings(us)=" + ddl.probeBefore().timingsMicros()
                + " error=" + ddl.probeBefore().error());
        System.out.println("[MSSQL DDL 드라이런 후 계획]\n" + ddl.probeAfter().plan() + "  timings(us)=" + ddl.probeAfter().timingsMicros()
                + " error=" + ddl.probeAfter().error());
        assertFalse(ddl.committed());
        assertTrue(ddl.probeAfter().plan().contains("change_it_big_status_idx"), "커밋 전 인덱스가 같은 트랜잭션의 계획에 보인다");
        assertEquals("0", rows(url, sa, "SELECT COUNT(*) FROM sys.indexes WHERE name = 'change_it_big_status_idx'").get(0).get(0),
                "드라이런 뒤 인덱스는 없다");
    }

    private static void msInsert(PreparedStatement ps, int id, String name, String note, String amount, Boolean flag,
                                 LocalDateTime updated, byte[] payload) throws SQLException {
        ps.setInt(1, id);
        ps.setString(2, name);
        ps.setString(3, note);
        ps.setBigDecimal(4, new BigDecimal(amount));
        if (flag == null) {
            ps.setNull(5, Types.BIT);
        } else {
            ps.setBoolean(5, flag);
        }
        ps.setObject(6, updated);
        ps.setBytes(7, payload);
        ps.executeUpdate();
    }

    private static void oraInsert(PreparedStatement ps, int id, String name, String note, String amount,
                                  LocalDateTime updated, byte[] payload) throws SQLException {
        ps.setInt(1, id);
        ps.setString(2, name);
        ps.setString(3, note);
        ps.setBigDecimal(4, new BigDecimal(amount));
        ps.setObject(5, updated);
        ps.setBytes(6, payload);
        ps.executeUpdate();
    }
}
