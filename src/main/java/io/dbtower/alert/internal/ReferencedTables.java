package io.dbtower.alert.internal;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL에서 참조하는 테이블 이름을 뽑는다 (심화 아크 2, I2). FROM/JOIN과 변경 문장의 대상 뒤 식별자를 best-effort로 긁고,
 * 스키마 수식자·따옴표·대괄호를 벗겨 마지막 세그먼트만 남긴다.
 *
 * 정직: 정규식 파싱은 서브쿼리·CTE·별칭·콤마 조인에서 오탐/누락이 있다(문헌이 지적하는 취약점).
 * 그래서 이 추출은 "후보"일 뿐이고, 실제로 존재하는 테이블인지는 describeSchema 결과와 교집합으로
 * 검증한다 — CTE 이름·별칭처럼 실재하지 않는 후보는 그 단계에서 자동 탈락한다.
 */
public final class ReferencedTables {

    // 주석·문자열 리터럴을 먼저 지워 그 안의 from/join 오탐을 막는다.
    private static final Pattern LINE_COMMENT = Pattern.compile("--.*?(?:\\r?\\n|$)");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    // FROM·JOIN 뒤, 그리고 변경 문장의 대상(UPDATE t / INSERT [IGNORE] INTO t / MERGE INTO t) 뒤의 첫 식별자.
    // DELETE FROM은 FROM으로 잡힌다. 변경 문장을 빼면 Top Query의 UPDATE 상세가 "참조 테이블을 찾지 못했습니다"였다(#73).
    // SELECT ... FOR UPDATE [OF t]와 MySQL ON DUPLICATE KEY UPDATE col = ...의 UPDATE는 대상이 아니라 뺀다
    private static final Pattern FROM_JOIN = Pattern.compile(
            "(?i)(?:\\b(?:from|join)|(?<!\\bfor\\s{1,10})(?<!\\bkey\\s{1,10})\\bupdate|\\binsert\\s+(?:ignore\\s+)?into|\\bmerge\\s+into)"
                    + "\\s+([\\w.`\"\\[\\]]+)");

    private ReferencedTables() {
    }

    public static Set<String> from(String sql) {
        if (sql == null || sql.isBlank()) {
            return Set.of();
        }
        String cleaned = STRING_LITERAL.matcher(
                BLOCK_COMMENT.matcher(
                        LINE_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ")).replaceAll(" '' ");
        Set<String> tables = new LinkedHashSet<>();
        Matcher m = FROM_JOIN.matcher(cleaned);
        while (m.find()) {
            String name = normalize(m.group(1));
            if (!name.isEmpty()) {
                tables.add(name);
            }
        }
        return tables;
    }

    /** 따옴표·백틱·대괄호 제거 후 스키마 수식(db.schema.table)의 마지막 세그먼트만 — describeSchema는 순수 테이블명을 담는다 */
    private static String normalize(String raw) {
        String s = raw.replaceAll("[`\"\\[\\]]", "");
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        return s.trim();
    }
}
