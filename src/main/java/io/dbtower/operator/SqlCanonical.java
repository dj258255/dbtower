package io.dbtower.operator;

/**
 * SQL 판정용 정규화 — 주석과 인용 구간을 같은 길이의 공백으로 지운 사본으로 "무엇으로 시작하나·세미콜론이 중간에
 * 있나·변경 키워드가 있나"를 판정한다. 원문은 그대로 실행하고 판정만 이 사본으로 한다.
 *
 * <p>explain 게이트(AbstractJdbcOperator.requireSelect)와 워크벤치 문장 분류기가 같은 규칙을 쓰도록 한 곳에 둔다.
 * 두 읽기 전용 게이트가 서로 다른 강도로 병존하다 주석 하나로 뚫린 전례가 있어서다.
 */
public final class SqlCanonical {

    private SqlCanonical() {
    }

    /**
     * 판정용 정규화 — 주석({@code --}, 중첩 가능한 블록 주석)과 인용 구간(문자열·식별자·달러 인용)을
     * 같은 길이의 공백으로 지운 사본을 만든다. 원문은 그대로 실행하고 판정만 이 사본으로 한다.
     *
     * <p>백슬래시는 <b>이스케이프로 보지 않는다</b>(PostgreSQL의 standard_conforming_strings=on 동작).
     * 이게 fail-closed인 이유: {@code 'a\'; DROP TABLE x}에서 백슬래시를 이스케이프로 보면 문자열이
     * 계속 이어진다고 판단해 세미콜론을 놓치지만(통과), 안 보면 문자열이 거기서 끝나 세미콜론을 발견한다(거부).
     * 놓치는 쪽보다 더 거부하는 쪽이 안전하다. 다만 PostgreSQL의 {@code E'...'}는 명세상 백슬래시가
     * 이스케이프라 그때만 예외로 처리한다 — {@code SELECT E'\''; DROP TABLE x}가 정확히 이 경로로 뚫렸었다.
     *
     * <p>인용이 닫히지 않으면 남은 전체를 삼키지만, 그런 SQL은 DB가 문법 오류로 거부하므로
     * "우리에게는 숨기면서 DB에서는 실행되는" 조합이 성립하지 않는다.
     */
    public static String canonical(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {          // 라인 주석
                while (i < n && sql.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {          // 블록 주석 (PostgreSQL은 중첩 허용)
                int depth = 0;
                while (i < n) {
                    if (sql.charAt(i) == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                        depth++;
                        out.append("  ");
                        i += 2;
                    } else if (sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                        depth--;
                        out.append("  ");
                        i += 2;
                        if (depth == 0) {
                            break;
                        }
                    } else {
                        out.append(' ');
                        i++;
                    }
                }
                continue;
            }
            if (c == '$') {                                                   // 달러 인용 $tag$ ... $tag$
                int close = sql.indexOf('$', i + 1);
                if (close > i && isDollarTag(sql, i + 1, close)) {
                    String tag = sql.substring(i, close + 1);
                    int end = sql.indexOf(tag, close + 1);
                    int stop = end < 0 ? n : end + tag.length();
                    out.append(" ".repeat(stop - i));
                    i = stop;
                    continue;
                }
            }
            if (c == '\'' || c == '"' || c == '`') {                          // 문자열·식별자 인용
                boolean backslashEscapes = c == '\'' && isEscapeStringPrefix(sql, i);
                out.append(' ');
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (backslashEscapes && d == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    if (d == c) {
                        if (i + 1 < n && sql.charAt(i + 1) == c) {            // '' "" `` 는 이스케이프된 인용부호
                            out.append("  ");
                            i += 2;
                            continue;
                        }
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(' ');
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 달러 인용 태그는 비었거나 영숫자·밑줄만 (PostgreSQL 규약) */
    private static boolean isDollarTag(String sql, int from, int toExclusive) {
        for (int k = from; k < toExclusive; k++) {
            char c = sql.charAt(k);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    /** PostgreSQL의 E'...' — 이 안에서만 백슬래시가 이스케이프다. 앞 글자가 식별자면 E가 아니라 이름의 끝이다. */
    private static boolean isEscapeStringPrefix(String sql, int quoteIndex) {
        if (quoteIndex == 0) {
            return false;
        }
        char prev = sql.charAt(quoteIndex - 1);
        if (prev != 'E' && prev != 'e') {
            return false;
        }
        return quoteIndex < 2 || !(Character.isLetterOrDigit(sql.charAt(quoteIndex - 2))
                || sql.charAt(quoteIndex - 2) == '_');
    }

    /**
     * 문장 구분자 세미콜론이 문장 <b>중간</b>에 있는지 검사한다(입력은 canonical 사본).
     * 끝에 하나 붙은 세미콜론(뒤가 공백뿐)은 정상 종결로 허용한다.
     */
    public static boolean hasStatementSeparator(String canonical) {
        int idx = canonical.indexOf(';');
        while (idx >= 0) {
            if (!canonical.substring(idx + 1).isBlank()) {
                return true;
            }
            idx = canonical.indexOf(';', idx + 1);
        }
        return false;
    }
}
