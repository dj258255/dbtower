package io.dbtower.operator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 플랜 변경 감지의 판정 기준 — 계획 원문에서 <b>형태(shape)</b>만 남기는 정규화 검증.
 * 원칙: 구조(노드·인덱스·대상)는 남기고 수치(비용·추정 행수)는 버린다. 수치가 남으면
 * 통계가 조금만 변해도 "가짜 변경"이 되고, 구조가 사라지면 진짜 플립을 놓친다.
 * 기종마다 계획 표현이 다르므로(PG JSON·MySQL JSON·MSSQL XML·Mongo winningPlan) 기종별로 검증한다.
 */
class PlanShapesTest {

    // ---------- PostgreSQL ----------

    @Test
    void PG_노드종류와_인덱스만_남기고_비용은_버린다() {
        String seq = """
                [{"Plan":{"Node Type":"Seq Scan","Relation Name":"orders","Startup Cost":0.00,
                "Total Cost":1234.56,"Plan Rows":50000,"Plans":[]}}]""";
        String shape = PlanShapes.fromPgJson(seq);
        assertThat(shape).isEqualTo("Seq Scan(orders)");
        assertThat(shape).doesNotContain("1234").doesNotContain("50000");
    }

    @Test
    void PG_같은_구조_다른_추정치는_같은_shape() {
        String a = """
                [{"Plan":{"Node Type":"Index Scan","Index Name":"idx_code","Plan Rows":10,"Plans":[]}}]""";
        String b = """
                [{"Plan":{"Node Type":"Index Scan","Index Name":"idx_code","Plan Rows":9999,"Plans":[]}}]""";
        assertThat(PlanShapes.fromPgJson(a)).isEqualTo(PlanShapes.fromPgJson(b));
    }

    @Test
    void PG_인덱스에서_풀스캔으로_바뀌면_다른_shape() {
        String idx = """
                [{"Plan":{"Node Type":"Index Scan","Index Name":"idx_code","Plans":[]}}]""";
        String seq = """
                [{"Plan":{"Node Type":"Seq Scan","Relation Name":"orders","Plans":[]}}]""";
        assertThat(PlanShapes.fromPgJson(idx)).isNotEqualTo(PlanShapes.fromPgJson(seq));
    }

    @Test
    void PG_중첩_계획은_트리로_직렬화() {
        String nested = """
                [{"Plan":{"Node Type":"Nested Loop","Plans":[
                  {"Node Type":"Seq Scan","Relation Name":"a","Plans":[]},
                  {"Node Type":"Index Scan","Index Name":"idx_b","Plans":[]}]}}]""";
        assertThat(PlanShapes.fromPgJson(nested))
                .isEqualTo("Nested Loop>[Seq Scan(a),Index Scan(idx_b)]");
    }

    // ---------- MySQL: EXPLAIN FORMAT=JSON ----------

    @Test
    void MySQL_access_type과_인덱스만_남긴다() {
        String full = """
                {"query_block":{"select_id":1,"cost_info":{"query_cost":"303.00"},
                "table":{"table_name":"products","access_type":"ALL","rows_examined_per_scan":3001}}}""";
        assertThat(PlanShapes.fromMysqlJson(full)).isEqualTo("ALL(products)");

        String ref = """
                {"query_block":{"table":{"table_name":"products","access_type":"ref",
                "key":"idx_code","rows_examined_per_scan":1}}}""";
        assertThat(PlanShapes.fromMysqlJson(ref)).isEqualTo("ref(products:idx_code)");
    }

    @Test
    void MySQL_풀스캔에서_인덱스로_바뀌면_다른_shape_같은_cost는_무관() {
        String a = """
                {"query_block":{"cost_info":{"query_cost":"303"},
                "table":{"table_name":"products","access_type":"ALL"}}}""";
        String b = """
                {"query_block":{"cost_info":{"query_cost":"999"},
                "table":{"table_name":"products","access_type":"ALL"}}}""";
        assertThat(PlanShapes.fromMysqlJson(a)).isEqualTo(PlanShapes.fromMysqlJson(b)); // cost 무관
        String idx = """
                {"query_block":{"table":{"table_name":"products","access_type":"ref","key":"idx_code"}}}""";
        assertThat(PlanShapes.fromMysqlJson(idx)).isNotEqualTo(PlanShapes.fromMysqlJson(a)); // 접근 방식 변화
    }

    // ---------- MongoDB: winningPlan ----------

    @Test
    void Mongo_stage와_인덱스만_남긴다() {
        String ixscan = """
                {"queryPlanner":{"winningPlan":{"stage":"FETCH","inputStage":
                {"stage":"IXSCAN","indexName":"idx_k","direction":"forward"}}}}""";
        assertThat(PlanShapes.fromMongoPlan(ixscan)).isEqualTo("FETCH>[IXSCAN(idx_k)]");

        String collscan = """
                {"queryPlanner":{"winningPlan":{"stage":"COLLSCAN","direction":"forward"}}}""";
        assertThat(PlanShapes.fromMongoPlan(collscan)).isEqualTo("COLLSCAN");
    }

    @Test
    void Mongo_인덱스에서_콜스캔으로_바뀌면_다른_shape() {
        String ix = """
                {"queryPlanner":{"winningPlan":{"stage":"FETCH","inputStage":
                {"stage":"IXSCAN","indexName":"idx_k"}}}}""";
        String coll = """
                {"queryPlanner":{"winningPlan":{"stage":"COLLSCAN"}}}""";
        assertThat(PlanShapes.fromMongoPlan(ix)).isNotEqualTo(PlanShapes.fromMongoPlan(coll));
    }

    // ---------- SQL Server: showplan XML ----------

    @Test
    void MSSQL_PhysicalOp과_인덱스만_남긴다() {
        String seek = """
                <ShowPlanXML><BatchSequence><Batch><Statements><StmtSimple><QueryPlan>
                <RelOp PhysicalOp="Index Seek" LogicalOp="Index Seek" EstimateRows="1">
                  <IndexScan><Object Index="[idx_code]" Table="[products]"/></IndexScan>
                </RelOp></QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>""";
        assertThat(PlanShapes.fromMssqlXml(seek)).isEqualTo("Index Seek(idx_code)");
    }

    @Test
    void MSSQL_추정행수가_달라도_같은_구조면_같은_shape() {
        String a = """
                <ShowPlanXML><RelOp PhysicalOp="Clustered Index Scan" EstimateRows="100">
                  <IndexScan><Object Index="[PK_t]"/></IndexScan></RelOp></ShowPlanXML>""";
        String b = """
                <ShowPlanXML><RelOp PhysicalOp="Clustered Index Scan" EstimateRows="99999">
                  <IndexScan><Object Index="[PK_t]"/></IndexScan></RelOp></ShowPlanXML>""";
        assertThat(PlanShapes.fromMssqlXml(a)).isEqualTo(PlanShapes.fromMssqlXml(b));
        assertThat(PlanShapes.fromMssqlXml(a)).isEqualTo("Clustered Index Scan(PK_t)");
    }

    // ---------- 텍스트 폴백 (Oracle 등) ----------

    @Test
    void 텍스트_계획은_숫자를_지워_구조만_비교() {
        String t1 = "-> Table scan on t  (cost=303 rows=3001)";
        String t2 = "-> Table scan on t  (cost=999 rows=8888)";
        assertThat(PlanShapes.fromText(t1)).isEqualTo(PlanShapes.fromText(t2));
    }

    @Test
    void Oracle_PHV_문자열은_그대로_식별자() {
        // Oracle은 plan_hash_value가 곧 형태 식별자 — PHV가 다르면 다른 플랜
        assertThat(PlanShapes.hash("PHV:111")).isNotEqualTo(PlanShapes.hash("PHV:222"));
        assertThat(PlanShapes.hash("PHV:111")).isEqualTo(PlanShapes.hash("PHV:111"));
    }
}
