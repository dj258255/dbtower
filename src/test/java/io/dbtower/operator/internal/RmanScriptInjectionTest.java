package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RMAN 스크립트(stdin) 주입 방어 — 접속 자격증명은 CONNECT 한 줄에 들어가므로, 개행이 섞이면
 * 그 다음 줄이 임의 RMAN 명령으로 실행된다. 백업 커맨드 실행 전에 거부되어야 한다(보안 리뷰 반영).
 */
class RmanScriptInjectionTest {

    private final BackupTools tools = new BackupTools("d", "d", "d", "r", "r", "r", "b", "o", "w", "a",
            "pb", "docker exec -i x rman", "/tmp");

    private OracleOperator op(String user, String password) {
        DatabaseInstance inst = new DatabaseInstance("o", DbmsType.ORACLE, "h", 1521, "FREEPDB1", user, password);
        return new OracleOperator(inst, Mockito.mock(ConnectionPools.class), tools);
    }

    @Test
    void 비밀번호에_개행이_있으면_PHYSICAL이_주입_위험으로_거부된다() {
        OracleOperator op = op("sys", "pw\nSHUTDOWN IMMEDIATE;");
        assertThatThrownBy(() -> op.backup(new BackupPolicy(null, BackupPolicy.BackupType.PHYSICAL)))
                .isInstanceOf(OperatorException.class)
                .hasMessageContaining("password");
    }

    @Test
    void 사용자명에_따옴표가_있으면_거부된다() {
        OracleOperator op = op("sys\"@evil", "pw");
        assertThatThrownBy(() -> op.backup(new BackupPolicy(null, BackupPolicy.BackupType.PHYSICAL)))
                .isInstanceOf(OperatorException.class)
                .hasMessageContaining("username");
    }
}
