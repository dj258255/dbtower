package io.dbtower.workbench.internal;

import io.dbtower.operator.model.ChangePlan.Kind;
import io.dbtower.workbench.internal.ChangeStatementParser.Parsed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 변경 문장에서 사본 조회를 만드는 규칙. 안전 경계는 실행 계층의 행 수 대조지만, 흔한 모양에서 틀리면 정상 변경이 매번 거부되므로
 * 주석·문자열·서브쿼리 안의 키워드를 최상위로 오인하지 않는지와, 행 대응을 확정할 수 없는 모양을 캡처 불가로 돌려주는지를 본다.
 */
class ChangeStatementParserTest {

    private static Parsed parse(String sql) {
        return ChangeStatementParser.parse(sql);
    }

    @Test
    void 단일_테이블_UPDATE는_원문_WHERE로_사본_조회를_만든다() {
        Parsed p = parse("UPDATE customers SET grade = 'VIP' WHERE id = 3;");

        assertEquals(Kind.UPDATE, p.kind());
        assertEquals("customers", p.table());
        assertEquals("SELECT * FROM customers WHERE id = 3", p.captureSql());
    }

    @Test
    void 스키마_한정과_별칭과_서브쿼리를_보존한다() {
        Parsed p = parse("update sample.customers c set c.grade = 'A' "
                + "where c.id in (select customer_id from orders where amount > 10)");

        assertEquals("sample.customers", p.table());
        assertEquals("SELECT * FROM sample.customers c where c.id in (select customer_id from orders where amount > 10)",
                p.captureSql());
    }

    @Test
    void 문자열과_주석과_달러_인용_안의_WHERE는_최상위가_아니다() {
        Parsed p = parse("UPDATE t SET note = 'where x', body = $$ where $$ /* where */ WHERE id = 1 -- where");

        assertEquals("SELECT * FROM t WHERE id = 1", p.captureSql(), "끝의 줄 주석이 락 절을 삼키지 않게 잘라야 한다");
    }

    @Test
    void MySQL_UPDATE의_ORDER_BY와_LIMIT도_사본_조회에_따라간다() {
        Parsed p = parse("UPDATE orders SET status = 'X' WHERE customer_id = 1 ORDER BY id LIMIT 10");

        assertEquals("SELECT * FROM orders WHERE customer_id = 1 ORDER BY id LIMIT 10", p.captureSql());
    }

    @Test
    void WHERE_없는_UPDATE는_테이블_전체를_사본으로_잡는다() {
        assertEquals("SELECT * FROM customers", parse("UPDATE customers SET grade = 'B'").captureSql());
    }

    @Test
    void 인용_식별자를_그대로_둔다() {
        Parsed p = parse("UPDATE \"Customers\" SET \"Grade\" = 'a' WHERE \"Id\" = 1");

        assertEquals("\"Customers\"", p.table());
        assertEquals("SELECT * FROM \"Customers\" WHERE \"Id\" = 1", p.captureSql());
    }

    @Test
    void 다중_테이블_변경은_캡처하지_않는다() {
        assertEquals(Kind.UNCAPTURED, parse("UPDATE a SET x = b.y FROM b WHERE a.id = b.id").kind());
        assertEquals(Kind.UNCAPTURED, parse("UPDATE a JOIN b ON a.id = b.id SET a.x = 1").kind());
        assertEquals(Kind.UNCAPTURED, parse("UPDATE a, b SET a.x = b.x WHERE a.id = b.id").kind());
        assertEquals(Kind.UNCAPTURED, parse("DELETE t1 FROM t1 JOIN t2 ON t1.id = t2.id").kind());
        assertEquals(Kind.UNCAPTURED, parse("DELETE FROM a USING b WHERE a.id = b.id").kind());
    }

    @Test
    void 서브쿼리_안의_쉼표와_JOIN은_다중_테이블이_아니다() {
        Parsed p = parse("UPDATE a SET x = (SELECT max(v) FROM b JOIN c ON b.id = c.id), y = 2 WHERE id IN (1, 2)");

        assertEquals(Kind.UPDATE, p.kind());
        assertEquals("SELECT * FROM a WHERE id IN (1, 2)", p.captureSql());
    }

    @Test
    void RETURNING은_영향_행_수_계약이_달라_캡처하지_않는다() {
        assertEquals(Kind.UNCAPTURED, parse("UPDATE a SET x = 1 WHERE id = 1 RETURNING *").kind());
        assertEquals(Kind.UNCAPTURED, parse("DELETE FROM a WHERE id = 1 RETURNING id").kind());
    }

    @Test
    void DELETE는_FROM이_있든_없든_대상_테이블을_찾는다() {
        Parsed standard = parse("DELETE FROM orders WHERE status = 'FAIL'");
        Parsed oracle = parse("DELETE sample.customers WHERE id = 3");

        assertEquals(Kind.DELETE, standard.kind());
        assertEquals("SELECT * FROM orders WHERE status = 'FAIL'", standard.captureSql());
        assertEquals("sample.customers", oracle.table());
        assertEquals("SELECT * FROM sample.customers WHERE id = 3", oracle.captureSql());
    }

    @Test
    void INSERT는_대상만_찾고_upsert는_캡처하지_않는다() {
        assertEquals("customers", parse("INSERT INTO customers (id, name) VALUES (4, 'x')").table());
        assertEquals(Kind.INSERT, parse("INSERT INTO t SELECT s.* FROM s JOIN u ON s.id = u.id").kind(),
                "SELECT 부분의 JOIN ... ON은 upsert가 아니다");
        assertEquals(Kind.INSERT, parse("INSERT INTO t (id) VALUES (1) ON CONFLICT DO NOTHING").kind());
        assertEquals(Kind.UNCAPTURED, parse("INSERT INTO t (id) VALUES (1) ON DUPLICATE KEY UPDATE id = 1").kind());
        assertEquals(Kind.UNCAPTURED, parse("INSERT INTO t (id) VALUES (1) ON CONFLICT (id) DO UPDATE SET id = 1").kind());
    }

    @Test
    void MongoDB_명령은_갱신_삭제_삽입_DDL을_가르고_사본을_확정할_수_없는_모양은_캡처하지_않는다() {
        Parsed many = ChangeStatementParser.parseMongo(
                "{\"update\": \"customers\", \"updates\": [{\"q\": {\"grade\": \"SILVER\"}, \"u\": {\"$set\": {\"grade\": \"GOLD\"}}, \"multi\": true}]}");
        assertEquals(Kind.UPDATE, many.kind());
        assertEquals("customers", many.table());

        assertEquals(Kind.UNCAPTURED, ChangeStatementParser.parseMongo(
                "{\"update\": \"c\", \"updates\": [{\"q\": {}, \"u\": {\"$set\": {\"a\": 1}}}, {\"q\": {}, \"u\": {\"$set\": {\"b\": 1}}}]}").kind(),
                "갱신 문이 여럿이면 사본 대응을 확정할 수 없다");
        assertEquals(Kind.UNCAPTURED, ChangeStatementParser.parseMongo(
                "{\"update\": \"c\", \"updates\": [{\"q\": {\"_id\": 9}, \"u\": {\"$set\": {\"a\": 1}}, \"upsert\": true}]}").kind(),
                "upsert는 없던 문서가 생길지 확정할 수 없다");
        assertEquals(Kind.DELETE, ChangeStatementParser.parseMongo("{\"delete\": \"c\", \"deletes\": [{\"q\": {\"_id\": 3}, \"limit\": 1}]}").kind());
        assertEquals(Kind.INSERT, ChangeStatementParser.parseMongo("{\"insert\": \"c\", \"documents\": [{\"_id\": 4}]}").kind());
        assertEquals(Kind.DDL, ChangeStatementParser.parseMongo(
                "{\"createIndexes\": \"c\", \"indexes\": [{\"key\": {\"grade\": 1}, \"name\": \"grade_1\"}]}").kind());
        assertEquals(Kind.UNCAPTURED, ChangeStatementParser.parseMongo(
                "{\"findAndModify\": \"c\", \"query\": {}, \"update\": {\"$set\": {\"a\": 1}}}").kind());
    }

    @Test
    void DDL과_행_대응이_없는_문장을_구분한다() {
        assertEquals(Kind.DDL, parse("ALTER TABLE customers ADD COLUMN memo VARCHAR(10)").kind());
        assertEquals(Kind.DDL, parse("CREATE INDEX idx_a ON a (x)").kind());
        assertEquals(Kind.UNCAPTURED, parse("WITH x AS (SELECT 1) UPDATE a SET y = 1").kind());
        assertEquals(Kind.UNCAPTURED, parse("MERGE INTO a USING b ON (a.id = b.id) WHEN MATCHED THEN UPDATE SET a.x = b.x").kind());
        assertNotNull(parse("REPLACE INTO a VALUES (1)").reason(), "캡처 불가는 이유를 함께 돌려준다");
    }
}
