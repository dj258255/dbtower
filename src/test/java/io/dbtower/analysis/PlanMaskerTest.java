package io.dbtower.analysis;

import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실행계획 가림 — 5기종 계획 형식별.
 *
 * <p>입력 계획은 지어낸 것이 아니라 실제로 뜬 것이다. MySQL·PostgreSQL은 E2 실험이 기록한 원문
 * (docs/experiments/ai-masking-tradeoff.md "계획에 리터럴이 찍혔는가" 절), Oracle은 gvenzl/oracle-free 23c에서
 * DBMS_XPLAN.DISPLAY로, MongoDB는 mongo:7에서 explain("queryPlanner")로 떴다. MSSQL만 형식 참고다 —
 * 컨테이너가 Apple Silicon에서 기동하지 않아(sqlservr: Invalid mapping of address) 이번에는 라이브로 못 떴다.
 *
 * <p>검증의 핵심은 두 방향이다. 값이 사라졌는가, 그리고 <b>진단 재료가 남았는가</b>.
 * 행수·비용·접근 경로·연산자가 함께 지워지면 가림은 성공해도 제품이 쓸모없어진다.
 */
class PlanMaskerTest {

    @Test
    @DisplayName("PostgreSQL — Filter의 값은 가리고 행수·비용·노드 이름은 남긴다")
    void postgres() {
        String plan = """
                [{"Plan": {"Node Type": "Seq Scan", "Relation Name": "exp_mask_orders", \
                "Startup Cost": 0.00, "Total Cost": 2269.00, "Plan Rows": 100000, "Plan Width": 8, \
                "Filter": "(created_at >= '2000-01-01'::date)"}}]""";

        String masked = PlanMasker.maskPlan(DbmsType.POSTGRESQL, plan);

        assertThat(masked).doesNotContain("2000-01-01");
        assertThat(masked).contains("'?'::date");            // 타입은 남는다 — 형변환 진단의 재료다
        assertThat(masked).contains("created_at >=");        // 컬럼·연산자도 남는다
        assertThat(masked).contains("\"Plan Rows\":100000"); // 추정 행수는 진단의 핵심이라 가리지 않는다
        assertThat(masked).contains("\"Total Cost\":2269.0");
        assertThat(masked).contains("Seq Scan");
    }

    @Test
    @DisplayName("PostgreSQL — 앞 와일드카드는 값을 지우고도 자리가 남는다")
    void postgresWildcard() {
        String plan = """
                [{"Plan": {"Node Type": "Seq Scan", "Filter": "((email)::text ~~ '%@gmail.com'::text)"}}]""";

        String masked = PlanMasker.maskPlan(DbmsType.POSTGRESQL, plan);

        assertThat(masked).doesNotContain("@gmail.com");
        assertThat(masked).contains("'%?'::text");
        assertThat(masked).contains("~~");
    }

    @Test
    @DisplayName("MySQL — attached_condition의 문자열과 숫자를 모두 가린다(숫자가 따옴표 없이 찍힌다)")
    void mysql() {
        String plan = """
                {"query_block": {"select_id": 1, "cost_info": {"query_cost": "40.36"}, \
                "table": {"table_name": "exp_mask_orders", "access_type": "range", \
                "key": "exp_mask_orders_amount_created_idx", "used_key_parts": ["amount"], \
                "rows_examined_per_scan": 200, "filtered": "33.33", "using_index": true, \
                "ranges": ["99000 <= amount <= 99100"], \
                "attached_condition": "(`sample`.`exp_mask_orders`.`created_at` >= DATE'2026-08-01')"}}}""";

        String masked = PlanMasker.maskPlan(DbmsType.MYSQL, plan);

        assertThat(masked).doesNotContain("2026-08-01").doesNotContain("99000").doesNotContain("99100");
        assertThat(masked).contains("DATE'?'");
        assertThat(masked).contains("? <= amount <= ?");
        assertThat(masked).contains("`exp_mask_orders`.`created_at`");   // 백틱 식별자는 값이 아니다
        assertThat(masked).contains("\"rows_examined_per_scan\":200");   // 표 1의 교훈: 선택도는 계획이 들고 있다
        assertThat(masked).contains("\"filtered\":\"33.33\"");
        assertThat(masked).contains("\"access_type\":\"range\"");
        assertThat(masked).contains("\"query_cost\":\"40.36\"");
    }

    @Test
    @DisplayName("Oracle — Predicate Information 절만 가리고 위 표와 연산 id는 남긴다")
    void oracle() {
        String plan = """
                Plan hash value: 1894917928

                ---------------------------------------------------------------
                | Id  | Operation                    | Name       | Rows  | Cost |
                ---------------------------------------------------------------
                |   0 | SELECT STATEMENT             |            |     1 |    2 |
                |*  1 |  TABLE ACCESS BY INDEX ROWID | CUSTOMERS  |     1 |    2 |
                |*  2 |   INDEX RANGE SCAN           | SYS_C008721|     1 |    1 |
                ---------------------------------------------------------------

                Predicate Information (identified by operation id):
                ---------------------------------------------------

                   1 - filter("GRADE"='VIP' AND "EMAIL" LIKE '%@gmail.com')
                   2 - access("ID">=99000 AND "ID"<=99100)
                """;

        String masked = PlanMasker.maskPlan(DbmsType.ORACLE, plan);

        assertThat(masked).doesNotContain("VIP").doesNotContain("@gmail.com")
                .doesNotContain("99000").doesNotContain("99100");
        assertThat(masked).contains("\"GRADE\"='?'");        // 큰따옴표는 Oracle 식별자라 남는다
        assertThat(masked).contains("\"EMAIL\" LIKE '%?'");  // 앞 와일드카드 자리 보존
        assertThat(masked).contains("   1 - filter(").contains("   2 - access(");
        assertThat(masked).contains("Plan hash value: 1894917928");
        assertThat(masked).contains("INDEX RANGE SCAN").contains("SYS_C008721");
    }

    @Test
    @DisplayName("MongoDB — filter의 잎만 가리고 stage·인덱스 이름은 남긴다")
    void mongo() {
        String plan = """
                {"namespace": "sample.customers", "winningPlan": {"stage": "COLLSCAN", \
                "filter": {"$and": [{"grade": {"$eq": "VIP"}}, {"email": {"$regex": "@gmail.com$"}}, \
                {"age": {"$gte": 30}}]}, "direction": "forward"}}""";

        String masked = PlanMasker.maskPlan(DbmsType.MONGODB, plan);

        assertThat(masked).doesNotContain("VIP").doesNotContain("@gmail.com").doesNotContain("30");
        assertThat(masked).contains("\"$eq\":\"?\"");
        assertThat(masked).contains("\"$regex\":\"?$\"");    // 정규식 앵커는 남는다(뒤 일치)
        assertThat(masked).contains("\"$gte\":\"?\"");
        assertThat(masked).contains("\"stage\":\"COLLSCAN\"");   // 값이지만 계획의 모양이다
        assertThat(masked).contains("\"direction\":\"forward\"");
        assertThat(masked).contains("\"grade\"").contains("\"email\"");   // 필드 이름은 키라 남는다
    }

    @Test
    @DisplayName("MSSQL — ScalarString·ConstValue만 가리고 연산자·행수는 남긴다")
    void mssql() {
        String plan = """
                <ShowPlanXML><BatchSequence><Batch><Statements><StmtSimple><QueryPlan>
                <RelOp PhysicalOp="Index Seek" LogicalOp="Index Seek" EstimateRows="1">
                  <IndexScan><Object Index="[idx_status]" Table="[orders]"/>
                    <Predicate><ScalarOperator ScalarString="[orders].[status]='PAID'">
                      <Const ConstValue="'PAID'"/>
                    </ScalarOperator></Predicate>
                  </IndexScan>
                </RelOp></QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>
                """;

        String masked = PlanMasker.maskPlan(DbmsType.MSSQL, plan);

        assertThat(masked).doesNotContain("PAID");
        assertThat(masked).contains("ScalarString=\"[orders].[status]='?'\"");   // 대괄호 식별자 보존
        assertThat(masked).contains("ConstValue=\"'?'\"");
        assertThat(masked).contains("EstimateRows=\"1\"").contains("PhysicalOp=\"Index Seek\"");
        assertThat(masked).contains("Index=\"[idx_status]\"");
    }

    @Test
    @DisplayName("형식이 깨지면 원문을 내보내지 않고 따옴표 값만이라도 가린다")
    void brokenJsonFallsBackClosed() {
        String broken = "{\"Plan\": {\"Filter\": \"(status = 'PAID')\"";   // 닫히지 않은 JSON

        String masked = PlanMasker.maskPlan(DbmsType.POSTGRESQL, broken);

        assertThat(masked).doesNotContain("PAID");
        assertThat(masked).contains("'?'");
    }

    @Test
    @DisplayName("설정이 꺼져 있으면 원문 그대로, 둘 다 켜져야 가린다")
    void settingGatesIt() {
        String plan = "[{\"Plan\": {\"Filter\": \"(status = 'PAID')\"}}]";

        assertThat(new PlanMasker(true, false).applyForAiPrompt(DbmsType.POSTGRESQL, plan)).isEqualTo(plan);
        assertThat(new PlanMasker(false, true).applyForAiPrompt(DbmsType.POSTGRESQL, plan)).isEqualTo(plan);
        assertThat(new PlanMasker(true, true).applyForAiPrompt(DbmsType.POSTGRESQL, plan)).doesNotContain("PAID");
    }

    @Test
    @DisplayName("빈 계획과 null은 그대로 — 가림이 계획 조회 실패 메시지를 바꾸지 않는다")
    void emptyPlan() {
        assertThat(PlanMasker.maskPlan(DbmsType.MYSQL, null)).isNull();
        assertThat(PlanMasker.maskPlan(DbmsType.MYSQL, "")).isEmpty();
    }
}
