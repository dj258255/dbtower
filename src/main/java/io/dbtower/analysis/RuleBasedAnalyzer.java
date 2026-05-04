package io.dbtower.analysis;

import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 실행계획을 규칙으로 훑어 비효율 신호를 찾는다.
 *
 * AI에게 실행계획을 통째로 맡기지 않고, 기종별 판단 기준을 코드/문서로 명시해 두는 이유:
 * 같은 입력에 같은 판정이 나와야 운영 도구로 신뢰할 수 있기 때문. (당근 KDMS도 판단 기준을 프롬프트에 명시)
 * 규칙 목록과 근거는 docs/ai-analysis-rules.md에 정리하며, LLM 연동(확장3) 시 이 규칙이 프롬프트가 된다.
 */
@Component
public class RuleBasedAnalyzer {

    public List<String> analyze(DbmsType type, String plan) {
        if (plan == null || plan.isBlank()) {
            return List.of();
        }
        List<String> findings = new ArrayList<>();
        switch (type) {
            case MYSQL -> {
                if (plan.contains("\"access_type\": \"ALL\"")) {
                    findings.add("테이블 풀스캔(access_type=ALL) — 인덱스가 없거나 타지 못하는 조건입니다");
                }
                if (plan.contains("\"using_filesort\": true")) {
                    findings.add("filesort 발생 — ORDER BY가 인덱스로 해결되지 않아 별도 정렬을 수행합니다");
                }
                if (plan.contains("\"using_temporary_table\": true")) {
                    findings.add("임시 테이블 생성 — GROUP BY/DISTINCT가 인덱스로 해결되지 않습니다");
                }
                if (plan.contains("\"access_type\": \"index\"")) {
                    findings.add("인덱스 풀스캔(access_type=index) — 인덱스 전체를 훑고 있어 범위 조건 검토가 필요합니다");
                }
            }
            case POSTGRESQL -> {
                if (plan.contains("Seq Scan")) {
                    findings.add("Seq Scan 발생 — 테이블 전체를 읽고 있습니다. WHERE 조건에 맞는 인덱스를 검토하세요");
                }
                if (plan.contains("Nested Loop") && plan.contains("Seq Scan")) {
                    findings.add("Nested Loop 안쪽에서 Seq Scan — 조인 키 인덱스가 없으면 행수에 비례해 급격히 느려집니다");
                }
                if (plan.contains("Sort Method: external")) {
                    findings.add("외부 정렬(디스크 스필) — work_mem을 넘는 정렬입니다");
                }
            }
            case MSSQL -> {
                if (plan.contains("TableScan") || plan.contains("Table Scan")) {
                    findings.add("Table Scan 발생 — 클러스터드 인덱스가 없거나 조건이 인덱스를 타지 못합니다");
                }
                if (plan.contains("ClusteredIndexScan")) {
                    findings.add("클러스터드 인덱스 풀스캔 — 사실상 테이블 전체를 읽는 것과 같습니다");
                }
                if (plan.contains("Sort")) {
                    findings.add("Sort 연산자 — 정렬 비용이 큰 경우 인덱스 정렬 순서 활용을 검토하세요");
                }
            }
        }
        if (findings.isEmpty()) {
            findings.add("규칙에 걸린 비효율 신호가 없습니다");
        }
        return findings;
    }
}
