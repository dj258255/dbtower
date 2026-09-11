package io.dbtower.workbench.internal;

import io.dbtower.workbench.internal.RowDiff.ChangeType;
import io.dbtower.workbench.internal.RowDiff.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 두 결과 집합의 차이 — 기종마다 다른 표기(1 대 1.00, T 구분자)는 같다고 보고, 실제로 다른 값만 짚는다. */
class RowDiffTest {

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    @Test
    void 키로_짝지어_바뀐_열만_짚고_기종별_표기_차이는_무시한다() {
        Result r = RowDiff.diff(
                List.of("id", "grade", "amount", "created_at"),
                List.of(row(1, "VIP", new BigDecimal("10.50"), "2026-09-10 12:00:00"), row(2, "GOLD", 5, "2026-09-10 12:00:00")),
                List.of("ID", "GRADE", "AMOUNT", "CREATED_AT"),
                List.of(row(new BigDecimal("1"), "VIP", 10.5, "2026-09-10T12:00"), row(2L, "SILVER", new BigDecimal("5.00"), "2026-09-10 12:00:00.0")),
                List.of("id"), 100);

        assertEquals(1, r.changed());
        assertEquals(1, r.unchanged());
        assertEquals(ChangeType.CHANGED, r.changes().get(0).type());
        assertEquals(List.of(2), r.changes().get(0).key());
        assertEquals(List.of(false, true, false, false),
                r.changes().get(0).cells().stream().map(RowDiff.Cell::changed).toList());
        assertTrue(r.notes().isEmpty(), "열 이름 대소문자 차이는 한쪽에만 있는 열이 아니다: " + r.notes());
    }

    @Test
    void 한쪽에만_있는_키는_추가와_삭제로_나온다() {
        Result r = RowDiff.diff(List.of("id", "v"), List.of(row(1, "a"), row(2, "b")),
                List.of("id", "v"), List.of(row(2, "b"), row(3, "c")), List.of("id"), 100);

        assertEquals(1, r.added());
        assertEquals(1, r.removed());
        assertEquals(0, r.changed());
        assertFalse(r.identical());
    }

    @Test
    void 키가_없으면_다중집합으로_비교해_중복_행_수까지_센다() {
        Result r = RowDiff.diff(List.of("status"), List.of(row("PAID"), row("PAID"), row("FAIL")),
                List.of("status"), List.of(row("PAID"), row("FAIL"), row("FAIL")), List.of(), 100);

        assertEquals(1, r.removed(), "PAID 한 행이 빠졌다");
        assertEquals(1, r.added(), "FAIL 한 행이 생겼다");
        assertEquals(2, r.unchanged());
    }

    @Test
    void 키가_유일하지_않으면_행_전체_비교로_내려가고_그렇다고_적는다() {
        Result r = RowDiff.diff(List.of("customer_id", "amount"), List.of(row(1, 10), row(1, 20)),
                List.of("customer_id", "amount"), List.of(row(1, 10), row(1, 20)), List.of("customer_id"), 100);

        assertTrue(r.identical());
        assertTrue(r.keyColumns().isEmpty());
        assertTrue(r.notes().get(0).contains("유일하지 않아"));
    }

    @Test
    void 한쪽에만_있는_열을_알리고_차이_행은_상한에서_자른다() {
        Result r = RowDiff.diff(List.of("id", "a"), List.of(row(1, "x"), row(2, "x"), row(3, "x")),
                List.of("id", "b"), List.of(row(1, "y"), row(2, "y"), row(3, "y")), List.of("id"), 2);

        assertEquals(3, r.changed(), "세는 것은 전부 센다");
        assertEquals(2, r.changes().size());
        assertTrue(r.truncated());
        assertTrue(r.notes().get(0).contains("한쪽에만 있는 열"));
    }
}
