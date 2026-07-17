package io.dbtower.operator.internal;

import io.dbtower.operator.model.SlowQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mongo 슬로우 시간대별 샘플링(레퍼런스 교훈) — 폭주 시간대 하나가 목록을 독점하지 않고
 * 버킷 라운드로빈으로 여러 시간대가 고르게 나오는지 고정한다.
 */
class MongoSlowSamplingTest {

    private static SlowQuery q(String text) {
        return new SlowQuery(text, 100, 10, "ts", null, -1, -1, null);
    }

    @Test
    void 폭주_시간대가_목록을_독점하지_않는다() {
        // hourA에 4건(밀리초 상위), hourB에 2건 — 상위 4개만 자르면 전부 A가 되지만
        // 라운드로빈 샘플링은 A,B,A,B로 섞는다.
        List<SlowQuery> fetched = List.of(q("a1"), q("a2"), q("a3"), q("a4"), q("b1"), q("b2"));
        List<Object> hours = List.of("A", "A", "A", "A", "B", "B");

        List<SlowQuery> out = MongoOperator.sampleAcrossHours(fetched, hours, 4);

        assertThat(out).extracting(SlowQuery::queryText).containsExactly("a1", "b1", "a2", "b2");
    }

    @Test
    void 버킷이_하나뿐이면_원래_순서를_유지한다() {
        List<SlowQuery> fetched = List.of(q("a1"), q("a2"), q("a3"));
        List<Object> hours = List.of("A", "A", "A");
        assertThat(MongoOperator.sampleAcrossHours(fetched, hours, 2))
                .extracting(SlowQuery::queryText).containsExactly("a1", "a2");
    }
}
