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
    void creds_경로는_형식_화이트리스트를_통과해야_한다() {
        // 경로가 URL로 들어가므로 조작 문자(.. ? # 공백)는 거부 — 발급 전에 막는다
        VaultCredentials vault = new VaultCredentials("http://localhost:18200", "t");
        assertThrows(OperatorException.class, () -> vault.resolve(instance("vault:../sys/seal")));
        assertThrows(OperatorException.class, () -> vault.resolve(instance("vault:a b")));
    }
}
