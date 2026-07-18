package io.dbtower.review;

import io.dbtower.review.internal.ChangeReviewRules;
import io.dbtower.review.internal.ChangeReviewRules.ColumnOp;
import io.dbtower.review.internal.ChangeReviewRules.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 변경 리뷰 규칙 판정 검증 — 락 위험(ALTER)·WHERE 없는 대량 변경·DROP·NOT NULL 추가를
 * 지적하고, 위험 없는 SQL은 "신호 없음"을 알린다. 파싱은 JSqlParser(구문 트리)라 정상 다중
 * 문장이면 parseLimited가 꺼지고, 파서가 못 다루는 SQL만 정규식 폴백으로 한계를 표기한다.
 * ADD/DROP 컬럼은 columnOps로 뽑아 서비스의 스키마 대조 재료가 된다.
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
    void 정상_다중_문장은_이제_정확히_파싱된다() {
        // 예전에는 정규식이라 다중 문장을 parseLimited로 표기했지만, JSqlParser는 각 문장을 정확히 본다
        Verdict v = rules.evaluate("ALTER TABLE a ADD COLUMN x INT; ALTER TABLE b DROP COLUMN y");
        assertFalse(v.parseLimited());
        assertTrue(has(v, "R-LOCK"));     // 첫 문장 ALTER
        assertTrue(has(v, "R-DROPCOL"));  // 둘째 문장 DROP COLUMN
    }

    @Test
    void 주석_안_세미콜론은_다중문장_오판을_만들지_않는다() {
        Verdict v = rules.evaluate("ALTER TABLE orders ADD COLUMN memo TEXT -- a; b; c\n");
        assertFalse(v.parseLimited());
    }

    @Test
    void 파서가_못_다루는_SQL은_정규식_폴백으로_한계를_표기한다() {
        // 종료 안 된 문자열 리터럴 — JSqlParser는 실패하고 정규식 폴백이 R-NOWHERE를 잡되 한계를 표기한다
        Verdict v = rules.evaluate("UPDATE orders SET data = '{broken ;;;");
        assertTrue(v.parseLimited());
        assertTrue(has(v, "R-NOWHERE"));
    }

    @Test
    void ADD와_DROP_컬럼을_columnOps로_뽑는다() {
        Verdict add = rules.evaluate("ALTER TABLE users ADD COLUMN `nickname` VARCHAR(32) NOT NULL");
        assertTrue(add.columnOps().stream()
                .anyMatch(op -> op.add() && op.table().equals("users") && op.column().equals("nickname")));

        Verdict drop = rules.evaluate("ALTER TABLE users DROP COLUMN nickname");
        assertTrue(drop.columnOps().stream()
                .anyMatch(op -> !op.add() && op.column().equals("nickname")));
    }

    @Test
    void 스키마_대조는_이미_있는_컬럼_추가와_없는_컬럼_삭제를_짚는다() {
        ColumnOp add = new ColumnOp("users", "email", true);
        assertTrue(rules.schemaMismatchLine(add, true).orElseThrow().startsWith("R-COL-EXISTS"));
        assertTrue(rules.schemaMismatchLine(add, false).isEmpty()); // 없는 컬럼 추가는 정상

        ColumnOp drop = new ColumnOp("users", "ghost", false);
        assertTrue(rules.schemaMismatchLine(drop, false).orElseThrow().startsWith("R-COL-MISSING"));
        assertTrue(rules.schemaMismatchLine(drop, true).isEmpty()); // 있는 컬럼 삭제는 정상
    }

    @Test
    void 대테이블_락_위험은_행수_임계로_확정된다() {
        assertTrue(rules.lockRiskLine("orders", 2_000_000).startsWith("R-LOCK-CONFIRM"));
        assertTrue(rules.lockRiskLine("small", 1_000).startsWith("R-LOCK-OK"));
    }
}
