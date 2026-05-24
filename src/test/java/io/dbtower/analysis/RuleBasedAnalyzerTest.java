package io.dbtower.analysis;

import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 기종별 실행계획 판정 규칙 검증. 규칙 문자열은 docs/ai-analysis-rules.md와 짝이다 —
 * 규칙을 바꾸면 문서도 같이 바꿔야 한다는 것을 테스트가 상기시킨다.
 */
class RuleBasedAnalyzerTest {

    private final RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer();

    private String findingsOf(DbmsType type, String plan) {
        return String.join(" / ", analyzer.analyze(type, plan));
    }

    @Test
    void mysql_풀스캔과_filesort를_잡는다() {
        String plan = "{\"access_type\": \"ALL\", \"using_filesort\": true}";
        String findings = findingsOf(DbmsType.MYSQL, plan);
        assertTrue(findings.contains("풀스캔"));
        assertTrue(findings.contains("filesort"));
    }

    @Test
    void postgresql_seq_scan을_잡는다() {
        assertTrue(findingsOf(DbmsType.POSTGRESQL, "Seq Scan on users").contains("Seq Scan"));
    }

    @Test
    void mssql_클러스터드_인덱스_스캔을_잡는다() {
        assertTrue(findingsOf(DbmsType.MSSQL, "<RelOp PhysicalOp=\"ClusteredIndexScan\"/>")
                .contains("클러스터드 인덱스 풀스캔"));
    }

    @Test
    void oracle_table_access_full과_정렬을_잡는다() {
        String plan = """
                | 1 | SORT ORDER BY      |       |
                | 2 |  TABLE ACCESS FULL | USERS |
                """;
        String findings = findingsOf(DbmsType.ORACLE, plan);
        assertTrue(findings.contains("TABLE ACCESS FULL"));
        assertTrue(findings.contains("SORT ORDER BY"));
    }

    @Test
    void mongodb_collscan과_인메모리_정렬을_잡는다() {
        String plan = """
                {"winningPlan": {"stage": "SORT", "inputStage": {"stage": "COLLSCAN"}}}
                """;
        String findings = findingsOf(DbmsType.MONGODB, plan);
        assertTrue(findings.contains("COLLSCAN"));
        assertTrue(findings.contains("인메모리 정렬"));
    }

    @Test
    void 신호가_없으면_없다고_명시한다() {
        List<String> findings = analyzer.analyze(DbmsType.MYSQL, "{\"access_type\": \"ref\"}");
        assertEquals(List.of("규칙에 걸린 비효율 신호가 없습니다"), findings);
    }

    @Test
    void 빈_실행계획은_빈_결과다() {
        assertTrue(analyzer.analyze(DbmsType.MYSQL, "").isEmpty());
        assertTrue(analyzer.analyze(DbmsType.MYSQL, null).isEmpty());
    }
}
