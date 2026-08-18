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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle 복원 검증 라이브 통합 — UNSUPPORTED가 아니라 실제 임포트까지 가는지 본다.
 *
 * Data Pump에는 MSSQL의 RESTORE VERIFYONLY 같은 단발 무결성 API가 없어서, "실제로 임포트해
 * 본다" 말고는 복원 가능성을 말할 방법이 없다. 그래서 임시 스키마로 REMAP_SCHEMA 임포트를 하고
 * 원본과 테이블 수를 맞춰 본다. 실행:
 *   docker compose up -d oracle
 *   # 최초 1회: docker/oracle-init.sql 의 DATAPUMP_IMP_FULL_DATABASE 부여가 되어 있어야 한다
 *   DBTOWER_ORACLE_IT=localhost:11521 ./gradlew test --tests '*OracleRestoreVerifyIT'
 *
 * 확인하는 것 셋:
 *   1) 덤프가 임시 스키마로 실제 임포트되고 테이블 수가 원본과 같다
 *   2) 검증이 끝나면 임시 스키마가 남지 않는다
 *   3) 원본 스키마는 손대지 않는다
 */
class OracleRestoreVerifyIT {

    private static final String GATE = "DBTOWER_ORACLE_IT";
    private static final String SERVICE = "FREEPDB1";
    private static final String USER = "sample";
    private static final String PASSWORD = "dbtower1234";

    private OracleOperator operator() {
        String[] hostPort = System.getenv(GATE).split(":");
        DatabaseInstance instance = new DatabaseInstance(
                "it-oracle", DbmsType.ORACLE, hostPort[0], Integer.parseInt(hostPort[1]),
                SERVICE, USER, PASSWORD);
        ConnectionPools pools = new ConnectionPools(
                new VaultCredentials("", ""), 120, 4, 5000, 600_000, 1_800_000, 30, 60_000);
        // Oracle 백업도 서버 사이드 API(DBMS_DATAPUMP)라 외부 CLI가 필요 없다 — RMAN 경로만 미설정
        BackupTools tools = new BackupTools(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "/tmp", false);
        return new OracleOperator(instance, pools, tools, "");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = ".+:\\d+")
    void 덤프가_임시_스키마로_실제_임포트되고_테이블수가_원본과_같다() throws Exception {
        OracleOperator op = operator();
        int expected = tableCount(op, USER.toUpperCase());
        assertTrue(expected > 0, "원본 스키마에 테이블이 있어야 비교가 성립한다");

        BackupResult backup = op.backup(new BackupPolicy("0 0 * * * *", BackupType.FULL));
        RestoreVerification v = op.verifyRestore(backup.location());

        assertEquals(RestoreVerification.Status.VERIFIED, v.status(), v.detail());
        assertNotNull(v.restoredObjectCount(), "실제 임포트까지 갔으면 개체 수가 채워진다: " + v.detail());
        assertEquals(expected, v.restoredObjectCount(),
                "임포트된 스키마의 테이블 수가 원본과 같아야 한다: " + v.detail());

        assertEquals(0, verifySchemaCount(op), "복원 검증 임시 스키마가 남았다");
        assertEquals(expected, tableCount(op, USER.toUpperCase()), "원본 스키마가 변했다");
    }

    private int tableCount(OracleOperator op, String owner) throws Exception {
        return scalar(op, "SELECT COUNT(*) FROM all_tables WHERE owner = '" + owner + "'");
    }

    private int verifySchemaCount(OracleOperator op) throws Exception {
        return scalar(op, "SELECT COUNT(*) FROM dba_users WHERE username LIKE 'DBTOWER_VERIFY%'");
    }

    private int scalar(OracleOperator op, String sql) throws Exception {
        try (Connection conn = op.open(); // 같은 패키지라 protected open()이 보인다
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
