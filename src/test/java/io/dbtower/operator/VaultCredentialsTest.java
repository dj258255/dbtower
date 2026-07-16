package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vault 동적 자격증명 — 접두 규약·게이트·경로 검증 계약 고정.
 * 발급·회전 수명주기는 라이브 e2e(VERIFICATION 89절 — 실제 Vault dev + PG 동적 계정)가 맡는다.
 */
class VaultCredentialsTest {

    private static DatabaseInstance instance(String username) {
        return new DatabaseInstance("i", DbmsType.POSTGRESQL, "h", 5432, "db", username, "pw");
    }

    @Test
    void vault_접두_인스턴스만_해석_대상이다() {
        VaultCredentials vault = new VaultCredentials("http://localhost:18200", "t");
        assertTrue(vault.applies(instance("vault:database/creds/monitor")));
        assertFalse(vault.applies(instance("postgres")));
    }

    @Test
    void vault_미설정이면_접두_인스턴스는_명확히_실패한다() {
        // 조용히 "vault:..." 문자열을 계정명으로 쓰는 오동작이 최악 — 명확한 실패가 계약
        VaultCredentials off = new VaultCredentials("", "");
        assertThrows(OperatorException.class, () -> off.resolve(instance("vault:database/creds/monitor")));
    }

    @Test
    void creds_경로는_database_creds_마운트로_봉인된다() {
        // 이 클래스 용도는 DB 동적 자격증명뿐 — 다른 시크릿 경로는 발급 전에 막는다(권한 상승면 차단).
        VaultCredentials vault = new VaultCredentials("http://localhost:18200", "t");
        // 임의 시크릿 경로 — 토큰 ACL이 닿아도 이 클래스로는 못 읽는다
        assertThrows(OperatorException.class, () -> vault.resolve(instance("vault:secret/data/prod-api-key")));
        // 경로 조작·마운트 탈출
        assertThrows(OperatorException.class, () -> vault.resolve(instance("vault:../sys/seal")));
        assertThrows(OperatorException.class, () -> vault.resolve(instance("vault:database/creds/a b")));
        // database/creds/ 아래의 다른 세그먼트 구조도 거부(정확히 롤 하나만)
        assertThrows(OperatorException.class, () -> vault.resolve(instance("vault:database/config/x")));
    }
}
