package io.dbtower.operator;

import io.dbtower.operator.model.RestoreVerification;
import io.dbtower.operator.internal.OracleOperator;

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

    /**
     * Oracle은 이제 임시 스키마로 REMAP_SCHEMA 임포트까지 간다(OracleRestoreVerifyIT).
     * 그래서 UNSUPPORTED는 "기종이라서"가 아니라 "이 계정에 임포트 권한이 없어서"로 좁혀졌고,
     * 그 판정은 대상에 붙어 session_privs를 봐야 나온다 — 라이브 IT의 몫이다.
     * 여기서 못 박는 것은 붙지도 못했을 때다: 확인을 못 했으면 통과로 위장하지 않는다.
     */
    @Test
    void 대상에_못_붙으면_되는_척하지_않고_FAILED로_낸다() {
        DatabaseInstance oracle = new DatabaseInstance(
                "ora", DbmsType.ORACLE, "127.0.0.1", 1, "FREEPDB1", "system", "pw"); // 닫힌 포트
        ConnectionPools pools = new ConnectionPools(
                new VaultCredentials("", ""), 5, 1, 1000, 60_000, 60_000, 30, 60_000);

        RestoreVerification v = new OracleOperator(oracle, pools, null)
                .verifyRestore("(server) DATA_PUMP_DIR/oracle-ora.dmp");

        assertEquals(RestoreVerification.Status.FAILED, v.status(), v.detail());
        assertNull(v.restoredObjectCount(), "복원을 못 했으면 개체 수는 비어 있어야 한다");
    }
}
