package io.dbtower.review;

import io.dbtower.review.internal.ChangeReviewRules;
import io.dbtower.review.internal.ChangeReviewRules.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 변경 리뷰 규칙 판정 검증 — 락 위험(ALTER)·WHERE 없는 대량 변경·DROP·NOT NULL 추가를
 * 지적하고, 위험 없는 SQL은 "신호 없음", 다중 문장은 파싱 한계를 정직 표기한다.
 */
class ChangeReviewRulesTest {

    private final ChangeReviewRules rules = new ChangeReviewRules();

    private boolean has(Verdict v, String code) {
        return v.findings().stream().anyMatch(f -> f.startsWith(code));
    }

    @Test
    void ALTER_TABLE은_락_위험과_대상_테이블을_잡는다() {
        Verdict v = rules.evaluate("ALTER TABLE orders ADD COLUMN memo VARCHAR(64) NULL");
        assertTrue(has(v, "R-LOCK"));
        assertEquals("orders", v.alterTable().orElseThrow());
    }

    @Test
    void DEFAULT_없는_NOT_NULL_추가를_지적한다() {
        Verdict v = rules.evaluate("ALTER TABLE users ADD COLUMN age INT NOT NULL");
        assertTrue(has(v, "R-NOTNULL"));
    }

    @Test
    void DEFAULT_있는_NOT_NULL은_지적하지_않는다() {
        Verdict v = rules.evaluate("ALTER TABLE users ADD COLUMN age INT NOT NULL DEFAULT 0");
        assertFalse(has(v, "R-NOTNULL"));
    }

    @Test
    void WHERE_없는_UPDATE와_DELETE를_잡는다() {
        assertTrue(has(rules.evaluate("UPDATE orders SET status = 'x'"), "R-NOWHERE"));
        assertTrue(has(rules.evaluate("DELETE FROM orders"), "R-NOWHERE"));
    }

    @Test
    void WHERE_있으면_대량변경으로_잡지_않는다() {
        assertFalse(has(rules.evaluate("UPDATE orders SET status = 'x' WHERE id = 1"), "R-NOWHERE"));
    }

    @Test
    void DROP_TABLE와_DROP_COLUMN과_TRUNCATE를_잡는다() {
        assertTrue(has(rules.evaluate("DROP TABLE orders"), "R-DROP"));
        assertTrue(has(rules.evaluate("ALTER TABLE orders DROP COLUMN memo"), "R-DROPCOL"));
        assertTrue(has(rules.evaluate("TRUNCATE orders"), "R-TRUNCATE"));
    }

    @Test
    void 위험이_없으면_신호_없음을_알린다() {
        Verdict v = rules.evaluate("CREATE INDEX idx_orders_user ON orders (user_id)");
        assertTrue(v.findings().get(0).contains("위험 신호가 없습니다"));
    }

    @Test
    void 다중_문장은_파싱_한계를_표기한다() {
        Verdict v = rules.evaluate("ALTER TABLE a ADD COLUMN x INT; ALTER TABLE b DROP COLUMN y");
        assertTrue(v.parseLimited());
    }

    @Test
    void 주석_안_세미콜론은_다중문장_오판을_만들지_않는다() {
        Verdict v = rules.evaluate("ALTER TABLE orders ADD COLUMN memo TEXT -- a; b; c\n");
        assertFalse(v.parseLimited());
    }

    @Test
    void 대테이블_락_위험은_행수_임계로_확정된다() {
        assertTrue(rules.lockRiskLine("orders", 2_000_000).startsWith("R-LOCK-CONFIRM"));
        assertTrue(rules.lockRiskLine("small", 1_000).startsWith("R-LOCK-OK"));
    }
}
