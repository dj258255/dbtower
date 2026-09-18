package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.model.BulkBatchOutcome;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.testsupport.TargetTableLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대량 일괄 변경의 배치 실행을 실제 DB에서 증명한다 — 오퍼레이터만으로, 서비스·티켓 없이.
 *
 * <p>확인하는 것은 넷이다.
 * <ol>
 *   <li><b>누락·중복 0</b> — 키가 비연속(1, 3, 7, 15…)이어도 조건에 맞는 행이 정확히 한 번씩 바뀐다</li>
 *   <li><b>조건 밖의 행은 건드리지 않는다</b> — 승인된 조건을 다시 쓰지 않고 키 범위만 덧붙이므로</li>
 *   <li><b>구간마다 따로 커밋한다</b> — 중간에 멈춰도 그때까지의 배치는 남는다(그것이 이 경로의 설계다)</li>
 *   <li><b>목표 행 수를 넘기면 커밋하지 않는다</b> — 키 열이 유일하지 않으면 그 구간이 목표보다 커진다</li>
 * </ol>
 *
 * <p>docker compose 대상이 필요하다: {@code DBTOWER_CONSOLE_IT=1 ./gradlew test --tests '*BulkChangeBatchIT'}
 */
class BulkChangeBatchIT {

    private static final String GATE = "DBTOWER_CONSOLE_IT";
    private static final String MYSQL_URL = "jdbc:mysql://127.0.0.1:13306/sample";
    private static final String PG_URL = "jdbc:postgresql://127.0.0.1:15432/sample";
    private static final ConsoleCredential MYSQL_CRED = new ConsoleCredential("root", "dbtower1234");
    private static final ConsoleCredential PG_CRED = new ConsoleCredential("postgres", "dbtower1234");

    /** Oracle·SQL Server는 게이트와 포트가 따로다 — SQL Server는 Rosetta VM에 띄운 2022를 쓴다(132절). */
    private static final String MSSQL_GATE = "DBTOWER_MSSQL_IT";
    private static final int MSSQL_PORT = Integer.parseInt(System.getenv().getOrDefault("DBTOWER_MSSQL_PORT", "11433"));
    private static final String ORACLE_URL = "jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1";
    private static final String MSSQL_URL = "jdbc:sqlserver://127.0.0.1:" + MSSQL_PORT
            + ";databaseName=master;encrypt=false;trustServerCertificate=true";
    private static final ConsoleCredential ORACLE_CRED = new ConsoleCredential("sample", "dbtower1234");
    private static final ConsoleCredential MSSQL_CRED = new ConsoleCredential("sa", "Dbtower1234!");

    /** 조건에 맞는 행이 총 몇 개인지 — 배치 크기(7)로 나누어떨어지지 않게 두어 마지막 배치가 짧게 끝나는 경우도 지난다 */
    private static final int MATCHING = 50;
    private static final int OTHERS = 30;
    private static final int BATCH = 7;

    /** 복합 키 시드에서 조건에 맞는 행 수 — 배치 크기로 나누어떨어지지 않게 둔다 */
    private static final int COMPOSITE_MATCHING = 40;

    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    /** 같은 대상 DB에 다른 실행이 붙어 bulk_it 을 지우지 않게 잡는다(#112) */
    private static TargetTableLock targetLock;

    @BeforeAll
    static void lockTargets() {
        targetLock = TargetTableLock.acquireIfEnabled(GATE, List.of(
                new TargetTableLock.Target(MYSQL_URL, MYSQL_CRED.username(), MYSQL_CRED.password()),
                new TargetTableLock.Target(PG_URL, PG_CRED.username(), PG_CRED.password())));
    }

    @AfterAll
    static void unlockTargets() {
        if (targetLock != null) {
            targetLock.close();
        }
    }

    @AfterEach
    void close() {
        pools.closeAll();
    }

    /**
     * 인스턴스 대역 — 기종마다 DB 이름이 다르다. Oracle은 PDB 이름(FREEPDB1)이고 SQL Server는 여기서 master를 쓴다.
     * 하나로 박아 두면 오퍼레이터가 만드는 JDBC URL이 엉뚱한 DB를 가리켜 "Invalid object name"·연결 시간 초과로 나온다.
     */
    private static DatabaseInstance instance(long id, DbmsType type, int port) {
        String dbName = switch (type) {
            case ORACLE -> "FREEPDB1";
            case MSSQL -> "master";
            default -> "sample";
        };
        String user = type == DbmsType.ORACLE ? "sample" : type == DbmsType.MSSQL ? "sa" : "unused";
        DatabaseInstance instance = new DatabaseInstance("bulk-it-" + id, type, "127.0.0.1", port, dbName,
                user, "unused");
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("MySQL — 비연속 키를 배치로 훑어 조건에 맞는 행만 한 번씩 바꾼다")
    void mysql() throws SQLException {
        seed(MYSQL_URL, MYSQL_CRED, "BIGINT");
        MySqlOperator op = new MySqlOperator(instance(9301, DbmsType.MYSQL, 13306), pools, null, null);
        try {
            runAndVerify(op, MYSQL_CRED, MYSQL_URL);
        } finally {
            drop(MYSQL_URL, MYSQL_CRED);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("PostgreSQL — 비연속 키를 배치로 훑어 조건에 맞는 행만 한 번씩 바꾼다")
    void postgres() throws SQLException {
        seed(PG_URL, PG_CRED, "BIGINT");
        PostgresOperator op = new PostgresOperator(instance(9302, DbmsType.POSTGRESQL, 15432), pools, null);
        try {
            runAndVerify(op, PG_CRED, PG_URL);
        } finally {
            drop(PG_URL, PG_CRED);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("MySQL — 키가 유일하지 않아 구간이 목표보다 커지면 커밋하지 않는다")
    void refusesOversizedBatch() throws SQLException {
        // grp 열은 값이 중복된다 — 이걸 배치 키로 삼으면 한 구간에 목표보다 많은 행이 들어온다
        seed(MYSQL_URL, MYSQL_CRED, "BIGINT");
        MySqlOperator op = new MySqlOperator(instance(9303, DbmsType.MYSQL, 13306), pools, null, null);
        BulkChangePlan plan = new BulkChangePlan("UPDATE bulk_it SET note = 'x'", "kind = 'M'",
                "bulk_it", "grp", 2, 30);
        try {
            List<Object> boundary = op.nextBulkBoundary(MYSQL_CRED, plan, null);
            assertThatThrownBy(() -> op.executeBulkBatch(MYSQL_CRED, plan, null, boundary))
                    .isInstanceOf(OperatorException.class)
                    .hasMessageContaining("목표 행 수를 넘겨 커밋하지 않았다");
            // 커밋하지 않았으니 한 행도 바뀌지 않았다
            assertThat(count(MYSQL_URL, MYSQL_CRED, "SELECT COUNT(*) FROM bulk_it WHERE note = 'x'")).isZero();
        } finally {
            drop(MYSQL_URL, MYSQL_CRED);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("Oracle — FETCH FIRST로 경계를 잡아 누락·중복 0")
    void oracle() throws SQLException {
        seed(ORACLE_URL, ORACLE_CRED, "NUMBER(19)");
        OracleOperator op = new OracleOperator(instance(9306, DbmsType.ORACLE, 11521), pools, null);
        try {
            runAndVerify(op, ORACLE_CRED, ORACLE_URL);
        } finally {
            drop(ORACLE_URL, ORACLE_CRED);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = MSSQL_GATE, matches = "1")
    @DisplayName("SQL Server — TOP (n)으로 경계를 잡아 누락·중복 0")
    void sqlServer() throws SQLException {
        seed(MSSQL_URL, MSSQL_CRED, "BIGINT");
        MsSqlOperator op = new MsSqlOperator(instance(9307, DbmsType.MSSQL, MSSQL_PORT), pools, null);
        try {
            runAndVerify(op, MSSQL_CRED, MSSQL_URL);
        } finally {
            drop(MSSQL_URL, MSSQL_CRED);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("MySQL — 복합 기본 키를 사전순으로 훑어 누락·중복 0")
    void mysqlCompositeKey() throws SQLException {
        seedComposite(MYSQL_URL, MYSQL_CRED);
        MySqlOperator op = new MySqlOperator(instance(9304, DbmsType.MYSQL, 13306), pools, null, null);
        try {
            runCompositeAndVerify(op, MYSQL_CRED, MYSQL_URL);
        } finally {
            exec(MYSQL_URL, MYSQL_CRED, "DROP TABLE IF EXISTS bulk_it_composite");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("PostgreSQL — 복합 기본 키를 사전순으로 훑어 누락·중복 0")
    void postgresCompositeKey() throws SQLException {
        seedComposite(PG_URL, PG_CRED);
        PostgresOperator op = new PostgresOperator(instance(9305, DbmsType.POSTGRESQL, 15432), pools, null);
        try {
            runCompositeAndVerify(op, PG_CRED, PG_URL);
        } finally {
            exec(PG_URL, PG_CRED, "DROP TABLE IF EXISTS bulk_it_composite");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("Oracle — 복합 기본 키를 사전순으로 훑어 누락·중복 0")
    void oracleCompositeKey() throws SQLException {
        seedComposite(ORACLE_URL, ORACLE_CRED);
        OracleOperator op = new OracleOperator(instance(9308, DbmsType.ORACLE, 11521), pools, null);
        try {
            runCompositeAndVerify(op, ORACLE_CRED, ORACLE_URL);
        } finally {
            dropComposite(ORACLE_URL, ORACLE_CRED);
        }
    }

    /**
     * SQL Server — 복합 키가 <b>문법부터</b> 다르다(#140).
     *
     * <p>v1.6.0이 이 기종에서 깨진 채 나갔다. 행 값 비교 {@code (a, b) > (?, ?)}를 SQL Server가 파싱하지 못해
     * ({@code Msg 4145}) 첫 경계 조회에서 죽었는데, 복합 키 테스트가 MySQL·PostgreSQL만 덮어 게이트를 통과했다.
     * 기종 확장과 복합 키가 <b>곱해지는 자리</b>를 테스트가 비워 두면 이렇게 된다.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = MSSQL_GATE, matches = "1")
    @DisplayName("SQL Server — 복합 기본 키를 펼친 형태로 훑어 누락·중복 0")
    void sqlServerCompositeKey() throws SQLException {
        seedComposite(MSSQL_URL, MSSQL_CRED);
        MsSqlOperator op = new MsSqlOperator(instance(9309, DbmsType.MSSQL, MSSQL_PORT), pools, null);
        try {
            runCompositeAndVerify(op, MSSQL_CRED, MSSQL_URL);
        } finally {
            dropComposite(MSSQL_URL, MSSQL_CRED);
        }
    }

    /**
     * 복합 키 {@code (shop_id, id)}를 사전순으로 훑는다.
     *
     * <p>이 시드가 노리는 것: 같은 {@code id}가 여러 {@code shop_id}에 걸쳐 있다. 열마다 부등호를 따로 쓰면
     * ({@code shop_id > ? AND id > ?}) 구간이 겹치거나 비어 누락·중복이 생기는데, 사전순 한 덩이 비교는
     * 그렇지 않다. 그 차이를 이 데이터가 드러낸다.
     */
    private void runCompositeAndVerify(AbstractJdbcOperator op, ConsoleCredential cred, String url)
            throws SQLException {
        BulkChangePlan plan = new BulkChangePlan("UPDATE bulk_it_composite SET note = 'done'", "kind = 'M'",
                "bulk_it_composite", List.of("shop_id", "id"), BATCH, 30);

        List<BulkBatchOutcome> batches = new ArrayList<>();
        List<Object> lastKey = null;
        while (true) {
            List<Object> toKey = op.nextBulkBoundary(cred, plan, lastKey);
            if (toKey == null) {
                break;
            }
            assertThat(toKey).as("경계는 키 열 수만큼 온다").hasSize(2);
            batches.add(op.executeBulkBatch(cred, plan, lastKey, toKey));
            lastKey = toKey;
        }

        long changed = count(url, cred, "SELECT COUNT(*) FROM bulk_it_composite WHERE note = 'done'");
        long outside = count(url, cred,
                "SELECT COUNT(*) FROM bulk_it_composite WHERE note = 'done' AND kind <> 'M'");
        long sum = batches.stream().mapToLong(BulkBatchOutcome::affectedRows).sum();

        assertThat(changed).as("조건에 맞는 행이 모두 바뀐다(누락 0)").isEqualTo(COMPOSITE_MATCHING);
        assertThat(outside).as("조건 밖은 건드리지 않는다").isZero();
        assertThat(sum).as("배치 영향 행 수의 합 = 실제 바뀐 행 수(중복 0)").isEqualTo(COMPOSITE_MATCHING);
        assertThat(batches).hasSizeGreaterThan(1);
        for (int i = 1; i < batches.size(); i++) {
            assertThat(batches.get(i).fromKey()).isEqualTo(batches.get(i - 1).toKey());
        }
        // 기록에 남을 모양 — 사람이 읽을 수 있어야 한다
        assertThat(BulkBatchOutcome.render(batches.getLast().toKey())).matches("\\(\\d+, \\d+\\)");
    }

    /** 같은 id가 여러 shop_id에 걸치도록 둔다 — 열별 부등호로는 못 자르는 모양이다. */
    private static void seedComposite(String url, ConsoleCredential cred) throws SQLException {
        dropComposite(url, cred);
        String varchar = url.startsWith("jdbc:oracle:") ? "VARCHAR2" : "VARCHAR";
        exec(url, cred,
                "CREATE TABLE bulk_it_composite (shop_id NUMERIC(19), id NUMERIC(19), kind " + varchar
                        + "(4), note " + varchar + "(20), PRIMARY KEY (shop_id, id))");
        try (Connection c = DriverManager.getConnection(url, cred.username(), cred.password())) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bulk_it_composite (shop_id, id, kind, note) VALUES (?, ?, ?, NULL)")) {
                int rows = 0;
                for (long shop = 1; shop <= 4; shop++) {
                    long id = 1;
                    for (int k = 0; k < 15; k++) {
                        ps.setLong(1, shop);
                        ps.setLong(2, id);
                        // 앞의 COMPOSITE_MATCHING개만 대상으로 둔다
                        ps.setString(3, rows < COMPOSITE_MATCHING ? "M" : "X");
                        ps.addBatch();
                        id += (k % 3) + 1;   // id도 비연속
                        rows++;
                    }
                }
                ps.executeBatch();
            }
            c.commit();
        }
    }

    private void runAndVerify(AbstractJdbcOperator op, ConsoleCredential cred, String url) throws SQLException {
        BulkChangePlan plan = new BulkChangePlan("UPDATE bulk_it SET note = 'done'", "kind = 'M'",
                "bulk_it", "id", BATCH, 30);

        List<BulkBatchOutcome> batches = new ArrayList<>();
        List<Object> lastKey = null;
        while (true) {
            List<Object> toKey = op.nextBulkBoundary(cred, plan, lastKey);
            if (toKey == null) {
                break;
            }
            batches.add(op.executeBulkBatch(cred, plan, lastKey, toKey));
            lastKey = toKey;
        }

        long changed = count(url, cred, "SELECT COUNT(*) FROM bulk_it WHERE note = 'done'");
        long changedOutsideCondition = count(url, cred,
                "SELECT COUNT(*) FROM bulk_it WHERE note = 'done' AND kind <> 'M'");
        long sumAffected = batches.stream().mapToLong(BulkBatchOutcome::affectedRows).sum();

        assertThat(changed).as("조건에 맞는 행이 모두 바뀐다(누락 0)").isEqualTo(MATCHING);
        assertThat(changedOutsideCondition).as("조건 밖은 건드리지 않는다").isZero();
        // 한 번씩만 바뀌었다 — 두 번 센 배치가 있으면 합이 실제 변경 행보다 커진다(중복 0)
        assertThat(sumAffected).as("배치 영향 행 수의 합 = 실제 바뀐 행 수(중복 0)").isEqualTo(MATCHING);
        assertThat(batches).as("배치 크기 %d로 %d행이면 여러 번 나뉜다", BATCH, MATCHING).hasSizeGreaterThan(1);
        for (BulkBatchOutcome b : batches) {
            assertThat(b.affectedRows()).as("배치 하나가 목표를 넘지 않는다").isLessThanOrEqualTo(BATCH);
        }
        // 첫 배치는 하한이 없고, 이어지는 배치의 하한은 앞 배치의 상한이다 — 구간이 겹치거나 비지 않는다
        assertThat(batches.get(0).fromKey()).isNull();
        for (int i = 1; i < batches.size(); i++) {
            assertThat(batches.get(i).fromKey()).isEqualTo(batches.get(i - 1).toKey());
        }
    }

    /**
     * 키를 비연속으로 만든다 — 1, 3, 7, 15…처럼 띄엄띄엄이고 조건에 맞는 행과 아닌 행이 섞인다.
     * 연속 키에서만 맞는 경계 계산(마지막 키 + 배치 크기 같은 산술)은 여기서 깨진다.
     */
    private static void seed(String url, ConsoleCredential cred, String keyType) throws SQLException {
        drop(url, cred);
        String varchar = url.startsWith("jdbc:oracle:") ? "VARCHAR2" : "VARCHAR";
        exec(url, cred,
                "CREATE TABLE bulk_it (id " + keyType + " PRIMARY KEY, grp " + keyType + ", kind " + varchar
                        + "(4), note " + varchar + "(20))",
                "CREATE INDEX bulk_it_kind_idx ON bulk_it (kind)");
        try (Connection c = DriverManager.getConnection(url, cred.username(), cred.password())) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bulk_it (id, grp, kind, note) VALUES (?, ?, ?, ?)")) {
                long key = 1;
                for (int i = 0; i < MATCHING + OTHERS; i++) {
                    ps.setLong(1, key);
                    ps.setLong(2, i / 10);        // 중복 값 — 유일하지 않은 키 시험에 쓴다
                    ps.setString(3, i % 8 == 3 ? "X" : "M");
                    ps.setString(4, null);
                    ps.addBatch();
                    key += (i % 5) + 1;           // 1~5씩 건너뛴다
                }
                ps.executeBatch();
            }
            c.commit();
        }
        // kind가 'M'인 행이 정확히 MATCHING개가 되도록 맞춘다 — 위 규칙으로는 개수가 딱 떨어지지 않는다
        exec(url, cred, "UPDATE bulk_it SET kind = 'M'");
        try (Connection c = DriverManager.getConnection(url, cred.username(), cred.password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM bulk_it ORDER BY id")) {
            List<Long> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
            StringBuilder others = new StringBuilder();
            for (int i = MATCHING; i < ids.size(); i++) {
                others.append(others.isEmpty() ? "" : ",").append(ids.get(i));
            }
            exec(url, cred, "UPDATE bulk_it SET kind = 'X' WHERE id IN (" + others + ")");
        }
    }

    /** Oracle에는 {@code DROP TABLE IF EXISTS}가 없어 없는 테이블의 오류를 삼킨다(ORA-00942). */
    private static void drop(String url, ConsoleCredential cred) throws SQLException {
        if (url.startsWith("jdbc:oracle:")) {
            execIgnoring(url, cred, "ORA-00942", "DROP TABLE bulk_it", "DROP TABLE bulk_it_composite");
            return;
        }
        exec(url, cred, "DROP TABLE IF EXISTS bulk_it");
    }

    private static void dropComposite(String url, ConsoleCredential cred) throws SQLException {
        if (url.startsWith("jdbc:oracle:")) {
            execIgnoring(url, cred, "ORA-00942", "DROP TABLE bulk_it_composite");
            return;
        }
        exec(url, cred, "DROP TABLE IF EXISTS bulk_it_composite");
    }

    private static void execIgnoring(String url, ConsoleCredential cred, String code, String... statements)
            throws SQLException {
        try (Connection c = DriverManager.getConnection(url, cred.username(), cred.password());
             Statement st = c.createStatement()) {
            for (String sql : statements) {
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    if (!String.valueOf(e.getMessage()).contains(code)) {
                        throw e;
                    }
                }
            }
        }
    }

    private static void exec(String url, ConsoleCredential cred, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, cred.username(), cred.password());
             Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }

    private static long count(String url, ConsoleCredential cred, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, cred.username(), cred.password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }
}
