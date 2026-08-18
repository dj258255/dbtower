package io.dbtower.analysis;

import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 실행계획을 규칙으로 훑어 비효율 신호를 찾는다.
 *
 * AI에게 실행계획을 통째로 맡기지 않고, 기종별 판단 기준을 코드/문서로 명시해 두는 이유:
 * 같은 입력에 같은 판정이 나와야 운영 도구로 신뢰할 수 있기 때문.
 * 규칙 목록과 근거는 docs/ai-analysis-rules.md에 정리하며, LLM 연동(확장3) 시 이 규칙이 프롬프트가 된다.
 */
@Component
public class RuleBasedAnalyzer {

    public List<String> analyze(DbmsType type, String plan) {
        if (plan == null || plan.isBlank()) {
            return List.of();
        }
        // 문장 switch가 아니라 표현식 switch다 — default가 없는 표현식 switch만 컴파일러가 exhaustive를
        // 강제한다. 문장 switch였을 때는 6번째 기종을 추가해도 컴파일·테스트가 통과한 뒤 런타임에
        // "규칙에 걸린 비효율 신호가 없습니다"를 돌려줬다. "분석했는데 문제 없음"과 "분석기가 이 기종을
        // 모름"이 같은 문자열로 나가는 것은 이 프로젝트의 위장 금지 원칙에 정면으로 어긋난다.
        List<String> findings = switch (type) {
            case MYSQL -> {
                List<String> f = new ArrayList<>();
                if (plan.contains("\"access_type\": \"ALL\"")) {
                    f.add("테이블 풀스캔(access_type=ALL) — 인덱스가 없거나 타지 못하는 조건입니다");
                }
                if (plan.contains("\"using_filesort\": true")) {
                    f.add("filesort 발생 — ORDER BY가 인덱스로 해결되지 않아 별도 정렬을 수행합니다");
                }
                if (plan.contains("\"using_temporary_table\": true")) {
                    f.add("임시 테이블 생성 — GROUP BY/DISTINCT가 인덱스로 해결되지 않습니다");
                }
                if (plan.contains("\"access_type\": \"index\"")) {
                    f.add("인덱스 풀스캔(access_type=index) — 인덱스 전체를 훑고 있어 범위 조건 검토가 필요합니다");
                }
                yield f;
            }
            case POSTGRESQL -> {
                List<String> f = new ArrayList<>();
                if (plan.contains("Seq Scan")) {
                    f.add("Seq Scan 발생 — 테이블 전체를 읽고 있습니다. WHERE 조건에 맞는 인덱스를 검토하세요");
                }
                if (plan.contains("Nested Loop") && plan.contains("Seq Scan")) {
                    f.add("Nested Loop 안쪽에서 Seq Scan — 조인 키 인덱스가 없으면 행수에 비례해 급격히 느려집니다");
                }
                if (plan.contains("Sort Method: external")) {
                    f.add("외부 정렬(디스크 스필) — work_mem을 넘는 정렬입니다");
                }
                yield f;
            }
            case MSSQL -> {
                List<String> f = new ArrayList<>();
                if (plan.contains("TableScan") || plan.contains("Table Scan")) {
                    f.add("Table Scan 발생 — 클러스터드 인덱스가 없거나 조건이 인덱스를 타지 못합니다");
                }
                if (plan.contains("ClusteredIndexScan")) {
                    f.add("클러스터드 인덱스 풀스캔 — 사실상 테이블 전체를 읽는 것과 같습니다");
                }
                if (plan.contains("Sort")) {
                    f.add("Sort 연산자 — 정렬 비용이 큰 경우 인덱스 정렬 순서 활용을 검토하세요");
                }
                yield f;
            }
            case ORACLE -> {
                List<String> f = new ArrayList<>();
                if (plan.contains("TABLE ACCESS FULL")) {
                    f.add("테이블 풀스캔(TABLE ACCESS FULL) — 조건에 맞는 인덱스가 없거나 타지 못합니다");
                }
                if (plan.contains("INDEX FULL SCAN")) {
                    f.add("인덱스 풀스캔(INDEX FULL SCAN) — 인덱스 전체를 훑고 있어 범위 조건 검토가 필요합니다");
                }
                if (plan.contains("SORT ORDER BY")) {
                    f.add("정렬 연산(SORT ORDER BY) — 인덱스가 정렬 순서를 제공하면 제거할 수 있습니다");
                }
                yield f;
            }
            case MONGODB -> {
                List<String> f = new ArrayList<>();
                if (plan.contains("COLLSCAN")) {
                    f.add("컬렉션 풀스캔(COLLSCAN) — 필터 조건을 받는 인덱스가 없습니다");
                }
                if (plan.contains("\"stage\": \"SORT\"")) {
                    f.add("인메모리 정렬(SORT 스테이지) — 인덱스가 정렬 순서를 제공하지 못해 메모리에서 정렬합니다");
                }
                yield f;
            }
        };
        if (findings.isEmpty()) {
            findings.add("규칙에 걸린 비효율 신호가 없습니다");
        }
        return findings;
    }
}
