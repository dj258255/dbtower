package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 콘솔 조회의 읽기 전용 트랜잭션 겹을 단독으로 증명한다. 분류기도 콘솔 계정 권한도 빼고, <b>쓰기 권한이 있는 계정</b>으로
 * 쓰기 문장을 직접 보내 DB가 거부하는지 본다 — 첫 방어선(분류기)과 마지막 방어선(계정 권한) 사이에 이 겹이 실제로 있는지.
 *
 * <p>docker compose의 대상 DB가 필요하다(VERIFICATION 128절):
 * {@code DBTOWER_CONSOLE_IT=1 ./gradlew test --tests '*ConsoleReadOnlyIT'}
 */
class ConsoleReadOnlyIT {

    private static final String GATE = "DBTOWER_CONSOLE_IT";

    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    @AfterEach
    void close() {
        pools.closeAll();
    }

    private static DatabaseInstance instance(long id, DbmsType type, int port, String dbName) {
        DatabaseInstance instance = new DatabaseInstance("console-it-" + id, type, "127.0.0.1", port, dbName, "unused", "unused");
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    private static void exec(String url, ConsoleCredential cred, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, cred.username(), cred.password());
             Statement st = c.createStatement()) {
            for (String sql : statements) {
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    // 준비 단계의 "이미 있음"(Oracle ORA-00955)은 재실행이라 무시한다
                    if (!String.valueOf(e.getMessage()).contains("ORA-00955")) {
                        throw e;
                    }
                }
            }
        }
    }

    private static long count(String url, ConsoleCredential cred, String table) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, cred.username(), cred.password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    void MySQL_읽기_전용_트랜잭션은_쓰기_권한이_있어도_INSERT와_DDL을_거부한다() throws Exception {
        MySqlOperator op = new MySqlOperator(instance(9101, DbmsType.MYSQL, 13306, "sample"), pools, null, null);
        ConsoleCredential root = new ConsoleCredential("root", "dbtower1234");
        exec(op.jdbcUrl(), root, "CREATE TABLE IF NOT EXISTS sample.console_ro_probe (id INT)",
                "DELETE FROM sample.console_ro_probe");

        OperatorException insert = assertThrows(OperatorException.class,
                () -> op.executeReadOnly(root, "INSERT INTO sample.console_ro_probe VALUES (1)", 10, 5));
        OperatorException ddl = assertThrows(OperatorException.class,
                () -> op.executeReadOnly(root, "CREATE TABLE sample.console_ro_probe_ddl (id INT)", 10, 5));

        System.out.println("[MySQL INSERT] " + insert.getMessage());
        System.out.println("[MySQL DDL]    " + ddl.getMessage());
        // 위 거부는 Connector/J의 클라이언트 텍스트 검사가 먼저 낸 것이다. 텍스트 검사는 경계가 아니므로
        // 서버 쪽 읽기 전용이 같은 실행 안에서 실제로 켜져 있는지 따로 잰다.
        Object serverFlag = op.executeReadOnly(root, "SELECT @@transaction_read_only", 10, 5).rows().get(0).get(0);
        System.out.println("[MySQL server @@transaction_read_only] " + serverFlag);
        assertEquals("1", String.valueOf(serverFlag));
        assertTrue(insert.getMessage().contains("read-only") || insert.getMessage().contains("READ ONLY"), insert.getMessage());
        assertEquals(0, count(op.jdbcUrl(), root, "sample.console_ro_probe"), "거부된 INSERT가 남으면 안 된다");
        assertEquals(1, op.executeReadOnly(root, "SELECT COUNT(*) FROM sample.console_ro_probe", 10, 5).rowCount(),
                "같은 풀로 읽기는 계속 된다(세션 상태가 망가지지 않았다)");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    void PostgreSQL_읽기_전용_트랜잭션은_INSERT와_읽기전용_해제_시도를_거부한다() throws Exception {
        PostgresOperator op = new PostgresOperator(instance(9102, DbmsType.POSTGRESQL, 15432, "sample"), pools, null);
        ConsoleCredential postgres = new ConsoleCredential("postgres", "dbtower1234");
        exec(op.jdbcUrl(), postgres, "CREATE TABLE IF NOT EXISTS console_ro_probe (id INT)",
                "DELETE FROM console_ro_probe", "CREATE SEQUENCE IF NOT EXISTS console_ro_seq");

        OperatorException insert = assertThrows(OperatorException.class,
                () -> op.executeReadOnly(postgres, "INSERT INTO console_ro_probe VALUES (1)", 10, 5));
        // 참조 Postgres MCP 서버를 뚫은 계열: 조회 모양으로 읽기 전용 설정을 끄려는 시도
        OperatorException unset = assertThrows(OperatorException.class,
                () -> op.executeReadOnly(postgres, "SELECT set_config('transaction_read_only', 'off', true)", 10, 5));
        OperatorException sequence = assertThrows(OperatorException.class,
                () -> op.executeReadOnly(postgres, "SELECT nextval('console_ro_seq')", 10, 5));

        System.out.println("[PG INSERT]     " + insert.getMessage());
        System.out.println("[PG set_config] " + unset.getMessage());
        System.out.println("[PG nextval]    " + sequence.getMessage());
        assertTrue(insert.getMessage().contains("read-only transaction"), insert.getMessage());
        assertEquals(0, count(op.jdbcUrl(), postgres, "console_ro_probe"));
    }

    /**
     * SQL Server 계열은 서버 쪽 읽기 전용 트랜잭션이 없고 드라이버도 setReadOnly를 무시한다 — 이 겹은 없고, 남는 방어는 끝의 롤백과
     * 조회 계정 권한뿐이라는 것을 그대로 증명한다(Azure SQL Edge, docker-compose.arm64.yml + docker/workbench-mssql.sql).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "DBTOWER_MSSQL_IT", matches = "1")
    void SQLServer계열은_읽기_전용_겹이_없어_끝의_롤백과_조회_계정_권한이_경계다() throws Exception {
        MsSqlOperator op = new MsSqlOperator(instance(9104, DbmsType.MSSQL, 11433, "sample"), pools, null);
        ConsoleCredential sa = new ConsoleCredential("sa", "Dbtower1234!");
        exec(op.jdbcUrl(), sa, "IF OBJECT_ID('dbo.console_ro_probe') IS NULL CREATE TABLE dbo.console_ro_probe (id INT)",
                "DELETE FROM dbo.console_ro_probe");

        assertDoesNotThrow(() -> op.executeReadOnly(sa, "INSERT INTO dbo.console_ro_probe VALUES (1)", 10, 5),
                "쓰기 권한 계정의 INSERT를 읽기 전용으로 거부하지 못한다(드라이버가 힌트를 무시)");
        assertEquals(0, count(op.jdbcUrl(), sa, "dbo.console_ro_probe"), "끝의 롤백으로 남지는 않는다");

        ConsoleCredential reader = new ConsoleCredential("dbtower_reader", "dbtower1234");
        OperatorException denied = assertThrows(OperatorException.class,
                () -> op.executeReadOnly(reader, "UPDATE dbo.customers SET grade = grade WHERE id = 1", 10, 5));
        System.out.println("[MSSQL reader UPDATE] " + denied.getMessage());
        assertTrue(denied.getMessage().contains("permission was denied"), denied.getMessage());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    void Oracle_읽기_전용_트랜잭션은_쓰기_권한이_있어도_INSERT를_거부한다() throws Exception {
        OracleOperator op = new OracleOperator(instance(9103, DbmsType.ORACLE, 11521, "FREEPDB1"), pools, null);
        ConsoleCredential owner = new ConsoleCredential("sample", "dbtower1234");
        exec(op.jdbcUrl(), owner, "CREATE TABLE console_ro_probe (id NUMBER)", "DELETE FROM console_ro_probe");

        Exception insert = assertThrows(Exception.class,
                () -> op.executeReadOnly(owner, "INSERT INTO console_ro_probe VALUES (1)", 10, 5));

        System.out.println("[Oracle INSERT] " + insert.getMessage());
        assertEquals(0, count(op.jdbcUrl(), owner, "console_ro_probe"), "거부든 롤백이든 행이 남으면 안 된다");
        assertTrue(insert.getMessage().contains("ORA-01456"), insert.getMessage());
    }
}
