package io.dbtower.workbench.internal;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlReferencesTest {

    @Test
    void FROM_JOIN_UPDATE_INTO_뒤의_테이블을_뽑는다() {
        assertEquals(Set.of("orders", "customers"),
                SqlReferences.tables("SELECT o.id FROM orders o JOIN sample.customers c ON c.id = o.customer_id"));
        assertEquals(Set.of("customers"), SqlReferences.tables("UPDATE customers SET grade = 'VIP' WHERE id = 1"));
        assertEquals(Set.of("audit"), SqlReferences.tables("INSERT INTO audit VALUES (1)"));
    }

    @Test
    void CTE_이름과_주석_문자열_속_이름은_테이블이_아니다() {
        assertEquals(Set.of("orders"),
                SqlReferences.tables("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent -- FROM ghost"));
        assertEquals(Set.of("orders"), SqlReferences.tables("SELECT 'from nowhere' FROM orders"));
    }

    @Test
    void MongoDB_명령과_빈_입력은_비어_있다() {
        assertTrue(SqlReferences.tables("{\"find\": \"customers\"}").isEmpty());
        assertTrue(SqlReferences.tables("  ").isEmpty());
    }
}
