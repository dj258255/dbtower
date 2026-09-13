package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 로그인 단계에 건 읽기 제한(SQL Server socketTimeout 5초, Oracle ReadTimeout 5초)은 로그인 뒤에 풀린다.
 *
 * <p>서버 사이드 BACKUP·RESTORE·Data Pump 같은 긴 작업이 5초에서 끊기면 안 된다(VERIFICATION 134절). 풀에서 받은 커넥션으로 서버가 7초 기다리는
 * 문장을 실행해 끝까지 받는지 본다 — 제한이 남아 있으면 5초에 읽기 시간 초과로 끊긴다.
 */
class NetworkTimeoutAfterLoginIT {

    private static final long MIN_COMPLETED_WAIT_MS = 6_500;

    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    @AfterEach
    void close() {
        pools.closeAll();
    }

    private static DatabaseInstance instance(long id, DbmsType type, int port, String db, String user, String password) {
        DatabaseInstance instance = new DatabaseInstance("timeout-it-" + id, type, "127.0.0.1", port, db, user, password);
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    private static long waitThrough(Connection c, String sql) throws SQLException {
        long t0 = System.nanoTime();
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DBTOWER_MSSQL_IT", matches = "1")
    void SQLServer_풀_커넥션은_로그인_뒤_7초_서버_대기를_끝까지_받는다() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("DBTOWER_MSSQL_PORT", "11433"));
        MsSqlOperator op = new MsSqlOperator(instance(9301, DbmsType.MSSQL, port, "sample", "sa", "Dbtower1234!"), pools, null);

        try (Connection c = op.open()) {
            int networkTimeout = c.getNetworkTimeout();
            long ms = waitThrough(c, "WAITFOR DELAY '00:00:07'");
            System.out.println("[MSSQL 로그인 뒤 7초 서버 대기] elapsed_ms=" + ms + " networkTimeout=" + networkTimeout);
            assertEquals(0, networkTimeout, "로그인 뒤에는 읽기 제한을 풀어 둔다");
            // 서버 대기와 클라이언트 단조 시계의 경계가 정확히 같지는 않다. 5초 제한 잔존과 구분되는 여유만 강제한다.
            assertTrue(ms >= MIN_COMPLETED_WAIT_MS, "서버 대기를 끝까지 받아야 한다: " + ms);
        }
        assertTrue(op.health().up());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DBTOWER_CONSOLE_IT", matches = "1")
    void Oracle_풀_커넥션은_로그인_뒤_7초_서버_대기를_끝까지_받는다() throws Exception {
        OracleOperator op = new OracleOperator(instance(9302, DbmsType.ORACLE, 11521, "FREEPDB1", "sample", "dbtower1234"), pools, null);

        try (Connection c = op.open()) {
            int networkTimeout = c.getNetworkTimeout();
            long ms = waitThrough(c, "BEGIN DBMS_SESSION.SLEEP(7); END;");
            System.out.println("[Oracle 로그인 뒤 7초 서버 대기] elapsed_ms=" + ms + " networkTimeout=" + networkTimeout);
            assertEquals(0, networkTimeout, "로그인 뒤에는 읽기 제한을 풀어 둔다");
            assertTrue(ms >= MIN_COMPLETED_WAIT_MS, "서버 대기를 끝까지 받아야 한다: " + ms);
        }
        assertTrue(op.health().up());
    }
}
