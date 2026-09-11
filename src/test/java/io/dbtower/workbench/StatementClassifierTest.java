package io.dbtower.workbench;

import io.dbtower.workbench.StatementClassifier.Classification;
import io.dbtower.workbench.StatementClassifier.Tier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 워크벤치 문장 분류기 — 허용 목록 방식이라 "애매하면 막는다"가 계약이다.
 * 과거에 실제로 뚫렸던 입력(주석 속 아포스트로피, E'...' 이스케이프, COMMIT 뒤 쓰기)을 회귀 입력으로 박는다.
 */
class StatementClassifierTest {

    private static Tier tier(String sql) {
        return StatementClassifier.classify(sql).tier();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM users WHERE id = 1",
            "  select 1;",
            "WITH t AS (SELECT 1 AS x) SELECT * FROM t",
            "(SELECT 1) UNION (SELECT 2)",
            "SHOW TABLES",
            "DESCRIBE users",
            "EXPLAIN SELECT * FROM orders",
            "EXPLAIN ANALYZE SELECT * FROM orders",
            "EXPLAIN DELETE FROM orders WHERE id = 1",
            "SELECT '; DROP TABLE x' AS s",
            "SELECT 1 -- ; drop table x",
            "SELECT updated_at, audit_delete_log FROM t",
            "SELECT \"update\" FROM t",
            "{\"find\": \"users\", \"filter\": {\"category\": 3}}",
            "{\"aggregate\": \"users\", \"pipeline\": [{\"$group\": {\"_id\": \"$category\"}}]}",
            "{\"count\": \"users\"}"
    })
    void 확실한_읽기는_즉시_실행으로_보낸다(String sql) {
        assertEquals(Tier.READ, tier(sql), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE users SET name = 'a' WHERE id = 1",
            "delete from orders where id = 3",
            "INSERT INTO t VALUES (1)",
            "ALTER TABLE t ADD c INT",
            "CREATE INDEX idx_a ON t (a)",
            "DROP TABLE t",
            "REPLACE INTO t VALUES (1)",
            "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d",
            "SELECT * INTO backup_t FROM t",
            "{\"update\": \"users\", \"updates\": [{\"q\": {}, \"u\": {\"$set\": {\"a\": 1}}}]}",
            "{\"createIndexes\": \"users\", \"indexes\": []}"
    })
    void 데이터나_구조를_바꾸는_문장은_승인_티켓으로_보낸다(String sql) {
        assertEquals(Tier.NEEDS_APPROVAL, tier(sql), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "COMMIT; DROP SCHEMA public CASCADE",
            "SELECT 1; DELETE FROM t",
            "SELECT 1 /* ' */; DROP TABLE x",
            "SELECT E'\\''; DROP TABLE x",
            "BEGIN",
            "COMMIT",
            "SET ROLE admin",
            "SET SESSION AUTHORIZATION postgres",
            "USE mysql",
            "TRUNCATE orders",
            "GRANT ALL ON t TO u",
            "CALL cleanup()",
            "EXEC sp_who",
            "DO $$ BEGIN PERFORM 1; END $$",
            "DROP DATABASE prod",
            "DROP SCHEMA public CASCADE",
            "DROP USER app",
            "SELECT pg_terminate_backend(123)",
            "SELECT set_config('default_transaction_read_only', 'off', false)",
            "SELECT * FROM t FOR UPDATE",
            "SELECT * FROM t LOCK IN SHARE MODE",
            "SELECT * FROM t INTO OUTFILE '/tmp/x'",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT nextval('orders_id_seq')",
            "SELECT orders_seq.nextval FROM dual",
            "EXPLAIN ANALYZE DELETE FROM t",
            "EXPLAIN (ANALYZE, BUFFERS) UPDATE t SET a = 1",
            "EXPLAIN PLAN FOR SELECT 1 FROM dual",
            "VACUUM",
            "LOCK TABLE t IN EXCLUSIVE MODE",
            "frobnicate the table",
            "{\"aggregate\": \"u\", \"pipeline\": [{\"$match\": {}}, {\"$out\": \"copy\"}]}",
            "{\"find\": \"u\", \"filter\": {\"$where\": \"sleep(100)\"}}",
            "{\"dropDatabase\": 1}",
            "{\"find\": ",
            "{}"
    })
    void 위험하거나_모르는_문장은_막는다(String sql) {
        assertEquals(Tier.BLOCKED, tier(sql), sql);
    }

    @Test
    void 주석에_숨긴_세미콜론과_키워드는_판정을_바꾸지_못한다() {
        // 주석 안의 ; 와 DROP은 지운 뒤 판정 — 오탐으로 막지 않는다
        assertEquals(Tier.READ, tier("SELECT 1 /* ; DROP TABLE x */"));
        // 반대로 주석 밖으로 새어 나온 두 번째 문장은 다중문으로 막는다(과거 requireSelect 우회 입력)
        Classification bypass = StatementClassifier.classify("SELECT 1 /* ' */; DROP TABLE x");
        assertEquals("MULTI_STATEMENT", bypass.kind());
    }

    @Test
    void 분류마다_사람이_읽을_사유를_단다() {
        Classification c = StatementClassifier.classify("TRUNCATE orders");
        assertEquals("TRUNCATE", c.kind());
        assertTrue(c.reason().contains("DELETE"), "대안까지 알려준다: " + c.reason());
        assertEquals("DATA_MODIFYING_CTE",
                StatementClassifier.classify("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d").kind());
    }
}
