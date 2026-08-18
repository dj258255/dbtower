package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.operator.model.BackupPolicy.BackupType;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.model.RestoreVerification;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL Server 복원 검증 라이브 통합 — VERIFYONLY가 아니라 실제 RESTORE까지 가는지 본다.
 *
 * VERIFYONLY는 "서버가 이 백업셋을 유효한 것으로 읽는가"까지고 "복원해서 쓸 수 있는가"의 증명이
 * 아니다. 그 구분을 코드가 실제로 지키는지는 라이브에서만 확인되므로 환경변수 게이트로 분리한다.
 * 실행:
 *   docker compose up -d mssql
 *   DBTOWER_MSSQL_IT=localhost:11433 ./gradlew test --tests '*MsSqlRestoreVerifyIT'
 *
 * 확인하는 것 셋:
 *   1) FULL 백업은 임시 DB로 실제 복원되고 복원된 사용자 테이블 수가 채워진다
 *   2) 로그 백업은 단독 복원이 안 되므로 VERIFYONLY로 남고 개체 수가 null이다(수준 차이가 값에 남는다)
 *   3) 어느 경로든 임시 DB가 남지 않는다
 */
class MsSqlRestoreVerifyIT {

    private static final String GATE = "DBTOWER_MSSQL_IT";
    private static final String DB = "pitrdemo";
    private static final String SA_PASSWORD = "Dbtower1234!";

    private MsSqlOperator operator() {
        String[] hostPort = System.getenv(GATE).split(":");
        DatabaseInstance instance = new DatabaseInstance(
                "it-mssql", DbmsType.MSSQL, hostPort[0], Integer.parseInt(hostPort[1]),
                DB, "sa", SA_PASSWORD);
        ConnectionPools pools = new ConnectionPools(
                new VaultCredentials("", ""), 30, 4, 5000, 600_000, 1_800_000, 30, 60_000);
        // MSSQL 백업은 서버 사이드 T-SQL이라 외부 클라이언트 명령이 하나도 필요 없다 — 전부 null
        BackupTools tools = new BackupTools(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "/tmp", false);
        return new MsSqlOperator(instance, pools, tools);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = ".+:\\d+")
    void FULL_백업은_임시DB로_실제_복원되고_테이블수가_채워진다() throws Exception {
        MsSqlOperator op = operator();
        int expected = userTableCount(op, DB);
        assertTrue(expected > 0, "원본에 사용자 테이블이 있어야 비교가 성립한다");

        BackupResult backup = op.backup(new BackupPolicy("0 0 * * * *", BackupType.FULL));
        RestoreVerification v = op.verifyRestore(backup.location());

        assertEquals(RestoreVerification.Status.VERIFIED, v.status(), v.detail());
        assertNotNull(v.restoredObjectCount(), "실제 복원까지 갔으면 개체 수가 채워진다: " + v.detail());
        assertEquals(expected, v.restoredObjectCount(),
                "복원본의 사용자 테이블 수가 원본과 같아야 한다: " + v.detail());
        assertNoLeftoverVerifyDatabase(op);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = ".+:\\d+")
    void 로그_백업은_단독_복원이_안_되어_VERIFYONLY로_남고_개체수가_없다() throws Exception {
        MsSqlOperator op = operator();
        // 로그 백업은 FULL이 선행돼야 체인이 선다
        op.backup(new BackupPolicy("0 0 * * * *", BackupType.FULL));
        BackupResult logBackup = op.backup(new BackupPolicy("0 0 * * * *", BackupType.LOG));

        RestoreVerification v = op.verifyRestore(logBackup.location());

        assertEquals(RestoreVerification.Status.VERIFIED, v.status(), v.detail());
        assertNull(v.restoredObjectCount(),
                "실제 복원을 안 했으면 개체 수는 비어 있어야 한다 — 이 null이 검증 수준의 차이다: " + v.detail());
        assertTrue(v.detail().contains("VERIFYONLY"), v.detail());
        assertNoLeftoverVerifyDatabase(op);
    }

    private int userTableCount(MsSqlOperator op, String db) throws Exception {
        return scalar(op, "SELECT COUNT(*) FROM [" + db + "].sys.tables WHERE is_ms_shipped = 0");
    }

    /** 검증이 끝나면 임시 DB는 한 개도 남으면 안 된다 — 남으면 관제탑이 대상 디스크를 먹는다. */
    private void assertNoLeftoverVerifyDatabase(MsSqlOperator op) throws Exception {
        assertEquals(0, scalar(op,
                        "SELECT COUNT(*) FROM sys.databases WHERE name LIKE 'dbtower_verify_%'"),
                "복원 검증 임시 DB가 남았다");
    }

    private int scalar(MsSqlOperator op, String sql) throws Exception {
        try (Connection conn = op.open(); // 같은 패키지라 protected open()이 보인다
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
