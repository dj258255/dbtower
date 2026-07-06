package io.dbtower.alert;

import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 플랜 변경 감지의 핵심 — 계획 원문에서 <b>형태(shape)</b>만 남기는 정규화 검증.
 * 비용·추정 행수는 통계가 조금만 변해도 흔들리므로, shape에 남으면 매번 "가짜 변경"이 된다.
 */
class PlanChangeTrackerTest {

    private final PlanChangeTracker tracker = new PlanChangeTracker(null, null);

    @Test
    void PG_계획은_노드종류와_인덱스만_남기고_비용은_버린다() {
        String seq = """
                [{"Plan":{"Node Type":"Seq Scan","Relation Name":"orders","Startup Cost":0.00,
                "Total Cost":1234.56,"Plan Rows":50000,"Plans":[]}}]""";
        String shape = tracker.shape(DbmsType.POSTGRESQL, seq);
        assertThat(shape).isEqualTo("Seq Scan(orders)");
        assertThat(shape).doesNotContain("1234").doesNotContain("50000");
    }

    @Test
    void 같은_구조_다른_추정치는_같은_shape다() {
        // 통계 갱신으로 추정 행수만 변한 경우 — 플랜 변경으로 오탐하면 안 된다
        String a = """
                [{"Plan":{"Node Type":"Index Scan","Index Name":"idx_code","Plan Rows":10,"Plans":[]}}]""";
        String b = """
                [{"Plan":{"Node Type":"Index Scan","Index Name":"idx_code","Plan Rows":9999,"Plans":[]}}]""";
        assertThat(tracker.shape(DbmsType.POSTGRESQL, a))
                .isEqualTo(tracker.shape(DbmsType.POSTGRESQL, b));
    }

    @Test
    void 인덱스가_바뀌면_다른_shape다() {
        String idx = """
                [{"Plan":{"Node Type":"Index Scan","Index Name":"idx_code","Plans":[]}}]""";
        String seq = """
                [{"Plan":{"Node Type":"Seq Scan","Relation Name":"orders","Plans":[]}}]""";
        assertThat(tracker.shape(DbmsType.POSTGRESQL, idx))
                .isNotEqualTo(tracker.shape(DbmsType.POSTGRESQL, seq));
    }

    @Test
    void 중첩_계획은_트리_구조로_직렬화된다() {
        String nested = """
                [{"Plan":{"Node Type":"Nested Loop","Plans":[
                  {"Node Type":"Seq Scan","Relation Name":"a","Plans":[]},
                  {"Node Type":"Index Scan","Index Name":"idx_b","Plans":[]}]}}]""";
        assertThat(tracker.shape(DbmsType.POSTGRESQL, nested))
                .isEqualTo("Nested Loop>[Seq Scan(a),Index Scan(idx_b)]");
    }

    @Test
    void 텍스트_계획은_숫자를_지워_구조만_비교한다() {
        // MySQL TREE 등 — cost/rows 수치가 흔들려도 같은 구조면 같은 shape
        String t1 = "-> Table scan on t  (cost=303 rows=3001)";
        String t2 = "-> Table scan on t  (cost=999 rows=8888)";
        assertThat(tracker.shape(DbmsType.MYSQL, t1)).isEqualTo(tracker.shape(DbmsType.MYSQL, t2));
    }
}
