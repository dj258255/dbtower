package io.dbtower.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 쿼리 마스킹 — 외부(웹훅·AI·MCP)로 SQL을 내보내기 직전, 값(리터럴)만 {@code ?}로 가리고
 * 구조(식별자·키워드·연산자)는 그대로 둔다.
 *
 * <p>왜 리터럴만인가: 진단력은 "어느 컬럼·인덱스·조인을 쓰는가"라는 구조에 있고, 민감정보는
 * {@code WHERE email = 'hong@x.com'}의 리터럴에 있다. 상위 쿼리 텍스트는 pg_stat_statements($1)·
 * MySQL digest(?)가 이미 정규화해 주지만, 사용자가 EXPLAIN·문의 창에 직접 친 원본 SQL에는 실값이
 * 그대로 남는다 — 그 경로를 위한 방어선이다. 과도 마스킹(식별자·구조까지 가림)은 진단을 무력화하므로 금한다.
 *
 * <p>왜 정규식이 아니라 문자 스캐너인가: 문자열 리터럴 {@code '...'}(가림)과 따옴표 식별자
 * {@code "..."}/{@code `...`}/{@code [...]}(보존)의 구분, {@code ''}/{@code \'} 이스케이프,
 * {@code $1} 파라미터 placeholder 보존, 숫자가 식별자의 꼬리({@code col1})인지 리터럴({@code = 100})인지의
 * 구분은 단순 정규식으로 정확히 못 잡는다. 한 번 훑는 스캐너가 이 경계들을 정직하게 처리한다.
 */
@Component
public class QueryMasker {

    private final boolean enabled;
    private final boolean maskAiPrompt;

    public QueryMasker(@Value("${dbtower.masking.enabled:true}") boolean enabled,
                       @Value("${dbtower.masking.mask-ai-prompt:true}") boolean maskAiPrompt) {
        this.enabled = enabled;
        this.maskAiPrompt = maskAiPrompt;
    }

    /** AI 프롬프트에도 마스킹을 적용할지 — 기본 true(#131 재판단). 사내 모델처럼 값이 밖으로 나가지 않을 때만 false로 끈다. */
    public boolean maskAiPrompt() {
        return maskAiPrompt;
    }

    /** 설정({@code dbtower.masking.enabled})이 켜져 있으면 리터럴 마스킹, 아니면 원문 그대로. */
    public String apply(String sql) {
        return enabled ? maskLiterals(sql) : sql;
    }

    /**
     * AI 프롬프트 전용 — 기본은 가림(#131 재판단). 외부 모델로 원문을 보내는 것을 기본으로 두지 않는다.
     * 대가는 리터럴 기반 판정(IN절 개수·상수 분포)의 정확도이고, 실측에서 42건 중 3건이다
     * (docs/experiments/ai-masking-levels.md). 사내 설치 모델처럼 값이 밖으로 나가지 않을 때만
     * mask-ai-prompt=false로 끈다. enabled와 mask-ai-prompt가 둘 다 켜져 있을 때만 가린다.
     */
    public String applyForAiPrompt(String sql) {
        return (enabled && maskAiPrompt) ? maskLiterals(sql) : sql;
    }

    /**
     * 순수 알고리즘 — 문자열/숫자 리터럴만 {@code ?}로 치환하고 식별자·키워드·구조·플레이스홀더는 보존.
     * Spring 없이도 테스트 가능하도록 static.
     */
    public static String maskLiterals(String sql) {
        return maskLiterals(sql, false);
    }

    /**
     * {@code keepWildcards}면 문자열 리터럴을 벗기지 않고 따옴표와 앞뒤 {@code %} 자리만 남긴다 —
     * {@code '%@gmail.com'}은 {@code '%?'}, {@code 'PAID'}는 {@code '?'}가 된다. 실행계획을 가릴 때 쓴다:
     * 앞 와일드카드는 인덱스를 못 쓰는 이유 자체라, 값을 지우면서 그 모양까지 지우면 진단이 사라진다(E2' 조건 D).
     */
    public static String maskLiterals(String sql, boolean keepWildcards) {
        return maskLiterals(sql, keepWildcards, true, AiMaskLevel.STRUCTURE, Clock.systemDefaultZone());
    }

    /**
     * 실행계획 안의 조건식 하나 — 가림 수준에 따라 값의 모양을 남기거나 통째로 지운다.
     * {@link AiMaskLevel#STRUCTURE}면 {@code %} 자리·자릿수·날짜 거리가 남고, {@link AiMaskLevel#FULL}이면
     * 전부 {@code ?}가 된다. 무엇이 왜 남는지는 {@link MaskShape} 주석에 있다.
     */
    public static String maskPlanLiterals(String expr, AiMaskLevel level, Clock clock) {
        return maskLiterals(expr, true, true, level, clock);
    }

    /**
     * 따옴표 문자열만 가리고 숫자는 남긴다 — 계획 원문처럼 숫자가 대부분 행수·비용인 자리에 쓴다.
     * 그 숫자를 가리면 민감정보는 줄지만 진단 재료가 사라진다({@link PlanMasker} 주석).
     */
    public static String maskQuotedLiterals(String sql) {
        return maskLiterals(sql, true, false, AiMaskLevel.STRUCTURE, Clock.systemDefaultZone());
    }

    private static String maskLiterals(String sql, boolean keepWildcards, boolean maskNumbers,
                                       AiMaskLevel level, Clock clock) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        int n = sql.length();
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);

            // 문자열 리터럴 '...' → ? (민감정보의 주 서식지)
            if (c == '\'') {
                int end = skipSingleQuoted(sql, i);
                if (keepWildcards) {
                    out.append(quotedWildcardShape(sql, i, end, level, clock));
                } else {
                    out.append('?');
                }
                i = end;
                continue;
            }

            // PG 파라미터 placeholder $1, $2 → 그대로 (리터럴 아님)
            if (c == '$' && i + 1 < n && isDigit(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && isDigit(sql.charAt(j))) {
                    j++;
                }
                out.append(sql, i, j);
                i = j;
                continue;
            }

            // PG 달러 인용 문자열 $$...$$ / $tag$...$tag$ → ? (문자열 리터럴의 다른 서식)
            if (c == '$') {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > 0) {
                    String tag = sql.substring(i, tagEnd);
                    int close = sql.indexOf(tag, tagEnd);
                    if (close >= 0) {
                        out.append('?');
                        i = close + tag.length();
                        continue;
                    }
                }
                // 매칭 실패 → 아래로 흘려 그대로 복사
            }

            // 라인 주석 -- ... 은 구조가 아니지만, 리터럴만 가린다는 원칙상 원문 유지(과도 마스킹 회피)
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int j = i;
                while (j < n && sql.charAt(j) != '\n') {
                    j++;
                }
                out.append(sql, i, j);
                i = j;
                continue;
            }

            // 블록 주석 /* ... */ 도 원문 유지
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int j = sql.indexOf("*/", i + 2);
                j = (j < 0) ? n : j + 2;
                out.append(sql, i, j);
                i = j;
                continue;
            }

            // 따옴표 식별자 "..."(표준/PG) · `...`(MySQL) → 리터럴이 아니라 컬럼/테이블명이므로 보존
            if (c == '"' || c == '`') {
                int j = skipDelimited(sql, i, c);
                out.append(sql, i, j);
                i = j;
                continue;
            }

            // 대괄호 식별자 [...] (SQL Server) → 보존
            if (c == '[') {
                int j = sql.indexOf(']', i + 1);
                j = (j < 0) ? n : j + 1;
                out.append(sql, i, j);
                i = j;
                continue;
            }

            // 식별자/키워드 [A-Za-z_]로 시작 → 뒤따르는 숫자까지 식별자의 일부(col1, user_2024)로 보존
            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < n && isIdentPart(sql.charAt(j))) {
                    j++;
                }
                out.append(sql, i, j);
                i = j;
                continue;
            }

            // 숫자 리터럴 — 여기 도달한 숫자는 식별자 꼬리가 아닌 순수 리터럴( = 100, LIMIT 50, IN (1,2) )
            if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(sql.charAt(i + 1)))) {
                int end = skipNumber(sql, i);
                out.append(maskNumbers
                        ? (keepWildcards ? MaskShape.number(sql.substring(i, end), level) : "?")
                        : sql.substring(i, end));
                i = end;
                continue;
            }

            // 그 외(연산자·괄호·콤마·공백·? placeholder 등) → 그대로
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** {@code [i, end)}의 문자열 리터럴을 따옴표와 앞뒤 % 자리만 남긴 모양으로 — {@code '%값%'} -> {@code '%?%'}. */
    private static String quotedWildcardShape(String s, int i, int end, AiMaskLevel level, Clock clock) {
        int bodyEnd = (end <= s.length() && end > i + 1 && s.charAt(end - 1) == '\'') ? end - 1 : end;
        String body = s.substring(i + 1, Math.max(i + 1, bodyEnd));
        return "'" + MaskShape.body(body, level, clock) + "'";
    }

    /** 여는 작은따옴표 위치 i에서 시작해 닫는 따옴표 다음 인덱스를 반환('' 및 \' 이스케이프 처리). */
    private static int skipSingleQuoted(String s, int i) {
        int n = s.length();
        int j = i + 1;
        while (j < n) {
            char c = s.charAt(j);
            if (c == '\\' && j + 1 < n) {   // MySQL 백슬래시 이스케이프(과소비는 안전 — 어차피 통째로 ?)
                j += 2;
                continue;
            }
            if (c == '\'') {
                if (j + 1 < n && s.charAt(j + 1) == '\'') {   // '' → 이스케이프된 따옴표
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return n;   // 미종료 문자열 — 끝까지 소비(안전)
    }

    /** 여는 구분자(" 또는 `) 위치 i에서 닫는 구분자 다음 인덱스를 반환(""/`` 이스케이프 처리). */
    private static int skipDelimited(String s, int i, char q) {
        int n = s.length();
        int j = i + 1;
        while (j < n) {
            if (s.charAt(j) == q) {
                if (j + 1 < n && s.charAt(j + 1) == q) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return n;
    }

    /** $ 위치 i가 달러 인용 여는 태그면 태그 끝 다음 인덱스, 아니면 -1($1 파라미터는 호출 전에 걸러짐). */
    private static int dollarTagEnd(String s, int i) {
        int n = s.length();
        int j = i + 1;
        // 태그 문자에서 $는 제외 — 포함하면 닫는 $까지 소비해 태그 종료를 영영 못 만난다
        while (j < n && (isIdentStart(s.charAt(j)) || isDigit(s.charAt(j)))) {
            j++;
        }
        if (j < n && s.charAt(j) == '$') {
            return j + 1;
        }
        return -1;
    }

    /** 숫자 리터럴(정수·소수·지수·16진) 끝 다음 인덱스. */
    private static int skipNumber(String s, int i) {
        int n = s.length();
        int j = i;
        if (s.charAt(j) == '0' && j + 1 < n && (s.charAt(j + 1) == 'x' || s.charAt(j + 1) == 'X')) {
            j += 2;
            while (j < n && isHex(s.charAt(j))) {
                j++;
            }
            return j;
        }
        while (j < n && isDigit(s.charAt(j))) {
            j++;
        }
        if (j < n && s.charAt(j) == '.') {
            j++;
            while (j < n && isDigit(s.charAt(j))) {
                j++;
            }
        }
        if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
            int k = j + 1;
            if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
                k++;
            }
            if (k < n && isDigit(s.charAt(k))) {
                j = k + 1;
                while (j < n && isDigit(s.charAt(j))) {
                    j++;
                }
            }
        }
        return j;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c) || c == '$';
    }
}
