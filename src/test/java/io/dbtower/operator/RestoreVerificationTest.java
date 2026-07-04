package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 3-값 상태 판정과 UNSUPPORTED 분기 검증.
 * UNSUPPORTED를 통과(VERIFIED)로 위장하지 않는다는 규칙이 코드로 고정돼 있는지 못 박는다.
 */
class RestoreVerificationTest {

    @Test
    void 세_값_팩토리는_상태와_카운트_규약을_지킨다() {
        RestoreVerification v = RestoreVerification.verified("ok", 3);
        assertEquals(RestoreVerification.Status.VERIFIED, v.status());
        assertEquals(3, v.restoredObjectCount());

        RestoreVerification f = RestoreVerification.failed("boom");
        assertEquals(RestoreVerification.Status.FAILED, f.status());
        assertNull(f.restoredObjectCount(), "실제 복원을 못 했으면 카운트는 null이어야 한다");

        RestoreVerification u = RestoreVerification.unsupported("범위 밖");
        assertEquals(RestoreVerification.Status.UNSUPPORTED, u.status());
        assertNull(u.restoredObjectCount());
    }

    @Test
    void Oracle은_서버_사이드_산출물이라_UNSUPPORTED로_정직하게_보고한다() {
        // verifyRestore는 커넥션을 열지 않고 즉시 UNSUPPORTED를 돌려주므로 pools 없이도 검증 가능
        DatabaseInstance oracle = new DatabaseInstance(
                "ora", DbmsType.ORACLE, "127.0.0.1", 1521, "FREEPDB1", "system", "pw");
        RestoreVerification v = new OracleOperator(oracle, null, null)
                .verifyRestore("(server) DATA_PUMP_DIR/oracle-ora.dmp");
        assertEquals(RestoreVerification.Status.UNSUPPORTED, v.status());
        assertTrue(v.detail().contains("IMPDP") || v.detail().contains("범위 밖"), v.detail());
    }
}
