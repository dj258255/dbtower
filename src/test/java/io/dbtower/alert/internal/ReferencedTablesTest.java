package io.dbtower.alert.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL 참조 테이블 추출 — best-effort(존재 검증은 describeSchema 교집합이 담당). */
class ReferencedTablesTest {

    @Test
    void 단순_from() {
        assertThat(ReferencedTables.from("SELECT * FROM orders")).containsExactly("orders");
    }

    @Test
    void join_대상도_뽑는다() {
        var t = ReferencedTables.from(
                "SELECT * FROM orders o JOIN customers c ON o.cid = c.id LEFT JOIN items i ON i.oid = o.id");
        assertThat(t).contains("orders", "customers", "items");
    }

    @Test
    void 스키마_수식자는_마지막_세그먼트만() {
        assertThat(ReferencedTables.from("SELECT * FROM public.orders")).containsExactly("orders");
        assertThat(ReferencedTables.from("SELECT * FROM shop.dbo.orders")).containsExactly("orders");
    }

    @Test
    void 따옴표_백틱_대괄호를_벗긴다() {
        assertThat(ReferencedTables.from("SELECT * FROM `orders`")).containsExactly("orders");
        assertThat(ReferencedTables.from("SELECT * FROM \"orders\"")).containsExactly("orders");
        assertThat(ReferencedTables.from("SELECT * FROM [orders]")).containsExactly("orders");
    }

    @Test
    void 문자열_리터럴_안의_from은_무시() {
        // WHERE 절 문자열에 'from' 이 들어가도 테이블로 오인하지 않는다
        var t = ReferencedTables.from("SELECT * FROM orders WHERE note = 'shipped from seoul'");
        assertThat(t).containsExactly("orders");
    }

    @Test
    void 주석_안의_from은_무시() {
        var t = ReferencedTables.from("SELECT * FROM orders -- from old_table\nWHERE id = 1");
        assertThat(t).containsExactly("orders");
    }

    @Test
    void 변경_문장의_대상_테이블도_뽑는다() {
        // #73 — Top Query의 UPDATE 상세에서 테이블 구조가 비었다
        assertThat(ReferencedTables.from("UPDATE exp_bp SET v = v + $1 WHERE id = $2")).containsExactly("exp_bp");
        assertThat(ReferencedTables.from("UPDATE orders o SET status = $1 FROM customers c WHERE c.id = o.customer_id"))
                .containsExactly("orders", "customers");
        assertThat(ReferencedTables.from("INSERT INTO payment_events (merchant_id, amount) VALUES ($1, $2)"))
                .containsExactly("payment_events");
        assertThat(ReferencedTables.from("INSERT IGNORE INTO `orders` SELECT * FROM staging_orders"))
                .containsExactly("orders", "staging_orders");
        assertThat(ReferencedTables.from("DELETE FROM dbo.[orders] WHERE id = @P0")).containsExactly("orders");
        assertThat(ReferencedTables.from("MERGE INTO orders t USING src s ON (t.id = s.id)")).containsExactly("orders");
    }

    @Test
    void 대상이_아닌_UPDATE는_테이블로_보지_않는다() {
        assertThat(ReferencedTables.from("SELECT * FROM orders WHERE id = $1 FOR UPDATE")).containsExactly("orders");
        assertThat(ReferencedTables.from("SELECT * FROM orders o JOIN customers c ON c.id = o.customer_id FOR  UPDATE OF o"))
                .containsExactly("orders", "customers");
        assertThat(ReferencedTables.from("INSERT INTO counters (k, n) VALUES (?, 1) ON DUPLICATE KEY UPDATE n = n + 1"))
                .containsExactly("counters");
    }

    @Test
    void null이나_빈문자열은_빈집합() {
        assertThat(ReferencedTables.from(null)).isEmpty();
        assertThat(ReferencedTables.from("   ")).isEmpty();
    }
}
