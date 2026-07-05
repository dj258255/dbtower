package io.dbtower.analysis;

import io.dbtower.analysis.DeepDiagnosis.CardinalityGap;
import io.dbtower.analysis.DeepDiagnosis.RootCause;
import io.dbtower.operator.ColumnSchema;
import io.dbtower.operator.IndexSchema;
import io.dbtower.operator.SchemaSnapshot;
import io.dbtower.operator.TableSchema;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 심층 원인 진단기(D9) 단위 검증 — 실제 실행 계획 파싱·카디널리티 괴리 판정·근본원인 규칙 매칭.
 * 특히 "MySQL·PG의 actual rows는 loops당 평균이라 총량은 loops 곱" 오독 함정을 명시적으로 검증한다.
 */
class DeepAnalyzerTest {

    private final DeepAnalyzer analyzer = new DeepAnalyzer();

    private SchemaSnapshot demoSchema() {
        return new SchemaSnapshot("MYSQL", "sample", List.of(
                new TableSchema("d9_demo",
                        List.of(new ColumnSchema("id", "int", false, 1),
                                new ColumnSchema("code", "varchar(20)", false, 2)),
                        List.of(new IndexSchema("idx_code", List.of("code"), false)))),
                false, 100);
    }

    @Test
    void PostgreSQL_추정_실제_괴리_노드를_지목한다() {
        String plan = """
                [{"Plan":{"Node Type":"Seq Scan","Relation Name":"d9_skew",
                "Plan Rows":5,"Actual Rows":348,"Actual Loops":1,"Rows Removed by Filter":49652,"Plans":[]}}]""";
        DeepDiagnosis d = analyzer.diagnose(DbmsType.POSTGRESQL, "SELECT * FROM d9_skew WHERE status='common'",
                plan, null);
        assertThat(d.worstGap()).isNotNull();
        assertThat(d.worstGap().estimatedRows()).isEqualTo(5);
        assertThat(d.worstGap().actualRows()).isEqualTo(348);
        assertThat(d.worstGap().ratio()).isGreaterThanOrEqualTo(10.0);
    }

    @Test
    void MySQL_actual_rows는_loops당_평균이라_총량은_loops를_곱한다() {
        // 안쪽 Index lookup: 추정 1행/loop, 실제 20행/loop, loops=10 → 총 실제 200행, 총 추정 10행
        String plan = String.join("\n",
                "-> Nested loop inner join  (cost=10 rows=100) (actual time=0.1..1.0 rows=200 loops=1)",
                "    -> Table scan on a  (cost=5 rows=10) (actual time=0.1..0.2 rows=10 loops=1)",
                "    -> Index lookup on b  (cost=1 rows=1) (actual time=0.01..0.02 rows=20 loops=10)");
        DeepDiagnosis d = analyzer.diagnose(DbmsType.MYSQL, "SELECT * FROM a JOIN b ON a.id=b.aid", plan, null);
        assertThat(d.worstGap()).isNotNull();
        // 최하위(가장 깊은) 노드 = Index lookup on b. loops 곱이 정확한지가 핵심.
        assertThat(d.worstGap().loops()).isEqualTo(10);
        assertThat(d.worstGap().estimatedRows()).isEqualTo(10);   // 1 × 10
        assertThat(d.worstGap().actualRows()).isEqualTo(200);     // 20 × 10 (평균 아님)
        assertThat(d.worstGap().ratio()).isEqualTo(20.0);         // 배수는 loops 상쇄로 per-loop과 동일
        assertThat(d.notes()).anyMatch(n -> n.contains("loops"));
    }

    @Test
    void Oracle_A_Rows는_이미_총량이라_loops를_곱하지_않는다_K접미사도_해석() {
        String plan = String.join("\n",
                "----------------------------------------------------------------",
                "| Id  | Operation          | Name   | Starts | E-Rows | A-Rows |",
                "----------------------------------------------------------------",
                "|   0 | SELECT STATEMENT   |        |      1 |        |      5 |",
                "|*  1 |  TABLE ACCESS FULL | ORDERS |      1 |     10 |  1000K |",
                "----------------------------------------------------------------");
        DeepDiagnosis d = analyzer.diagnose(DbmsType.ORACLE, "SELECT * FROM orders", plan, null);
        assertThat(d.worstGap()).isNotNull();
        assertThat(d.worstGap().loops()).isEqualTo(1);            // Oracle은 곱하지 않음
        assertThat(d.worstGap().estimatedRows()).isEqualTo(10);
        assertThat(d.worstGap().actualRows()).isEqualTo(1_000_000); // 1000K 해석
    }

    @Test
    void 암시적_형변환_문자열컬럼을_숫자와_비교하면_지목한다() {
        DeepDiagnosis d = analyzer.diagnose(DbmsType.MYSQL,
                "SELECT * FROM d9_demo WHERE code = 12345", "{}", demoSchema());
        assertThat(d.rootCauses()).extracting(RootCause::cause).contains("암시적 형변환");
    }

    @Test
    void 문자열_리터럴로_비교하면_형변환으로_보지_않는다() {
        DeepDiagnosis d = analyzer.diagnose(DbmsType.MYSQL,
                "SELECT * FROM d9_demo WHERE code = '12345'", "{}", demoSchema());
        assertThat(d.rootCauses()).extracting(RootCause::cause).doesNotContain("암시적 형변환");
    }

    @Test
    void 컬럼에_함수를_씌우면_지목한다() {
        DeepDiagnosis d = analyzer.diagnose(DbmsType.POSTGRESQL,
                "SELECT * FROM t WHERE YEAR(created) = 2024", "[]", null);
        assertThat(d.rootCauses()).extracting(RootCause::cause).contains("컬럼에 함수/표현식");
    }

    @Test
    void 앞_와일드카드_LIKE를_지목한다() {
        DeepDiagnosis d = analyzer.diagnose(DbmsType.POSTGRESQL,
                "SELECT * FROM t WHERE name LIKE '%abc%'", "[]", null);
        assertThat(d.rootCauses()).extracting(RootCause::cause).contains("앞 와일드카드 LIKE");
    }

    @Test
    void 복합인덱스_선두가_아닌_컬럼만_조건이면_지목한다() {
        SchemaSnapshot schema = new SchemaSnapshot("MYSQL", "sample", List.of(
                new TableSchema("t",
                        List.of(new ColumnSchema("a", "int", false, 1),
                                new ColumnSchema("b", "int", false, 2)),
                        List.of(new IndexSchema("idx_ab", List.of("a", "b"), false)))),
                false, 100);
        DeepDiagnosis d = analyzer.diagnose(DbmsType.MYSQL, "SELECT * FROM t WHERE b = 5", "{}", schema);
        assertThat(d.rootCauses()).extracting(RootCause::cause).contains("복합 인덱스 선두 누락");
    }

    @Test
    void SQLServer_CONVERT_IMPLICIT_경고를_신호로_쓴다() {
        String plan = "<ShowPlanXML><Warnings><PlanAffectingConvert Expression=\"CONVERT_IMPLICIT(int,[code])\"/>"
                + "</Warnings></ShowPlanXML>";
        DeepDiagnosis d = analyzer.diagnose(DbmsType.MSSQL, "SELECT * FROM t WHERE code = 1", plan, null);
        assertThat(d.rootCauses()).extracting(RootCause::cause).contains("암시적 형변환");
    }

    @Test
    void MongoDB_docsExamined_대비_nReturned_비율로_스캔낭비를_짚는다() {
        String plan = """
                {"queryPlanner":{"winningPlan":{"stage":"COLLSCAN"}},
                "executionStats":{"nReturned":2,"totalDocsExamined":20000,"totalKeysExamined":0}}""";
        DeepDiagnosis d = analyzer.diagnose(DbmsType.MONGODB, "{\"find\":\"c\",\"filter\":{\"x\":1}}", plan, null);
        assertThat(d.worstGap()).isNotNull();
        assertThat(d.worstGap().actualRows()).isEqualTo(20000); // docsExamined
        assertThat(d.worstGap().estimatedRows()).isEqualTo(2);  // nReturned
        assertThat(d.rootCauses()).extracting(RootCause::cause)
                .anyMatch(c -> c.contains("COLLSCAN"));
    }

    @Test
    void 괴리만_크고_구조적_신호가_없으면_통계노후를_후보로_든다() {
        String plan = """
                [{"Plan":{"Node Type":"Index Scan","Relation Name":"t",
                "Plan Rows":1,"Actual Rows":5000,"Actual Loops":1,"Plans":[]}}]""";
        DeepDiagnosis d = analyzer.diagnose(DbmsType.POSTGRESQL, "SELECT * FROM t WHERE k = 7", plan, null);
        assertThat(d.rootCauses()).extracting(RootCause::cause).contains("통계 노후(후보)");
    }

    @Test
    void 괴리도_규칙도_없으면_비어있는_진단을_돌려준다() {
        String plan = """
                [{"Plan":{"Node Type":"Index Scan","Relation Name":"t",
                "Plan Rows":100,"Actual Rows":98,"Actual Loops":1,"Plans":[]}}]""";
        DeepDiagnosis d = analyzer.diagnose(DbmsType.POSTGRESQL, "SELECT * FROM t WHERE id = 7", plan, null);
        assertThat(d.worstGap()).isNull();
        assertThat(d.rootCauses()).isEmpty();
    }
}
