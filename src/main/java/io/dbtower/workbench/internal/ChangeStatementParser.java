package io.dbtower.workbench.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.operator.model.ChangePlan.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 승인된 변경 문장에서 "어느 테이블의 어느 행이 바뀌는가"를 뽑아 변경 전 행 조회를 만든다.
 *
 * <p>이 파서는 안전 경계가 아니다. 캡처 조회가 실제 변경 대상과 어긋나면(파싱 실수·동시 변경) 실행 계층이 영향 행 수와
 * 캡처 행 수를 같은 트랜잭션 안에서 대조해 되돌린다. 그래서 여기서는 흔한 단일 테이블 UPDATE·DELETE·INSERT만 캡처
 * 대상으로 받고, 다중 테이블·upsert·CTE처럼 전후 행 대응을 확정할 수 없는 모양은 캡처 불가로 정직하게 돌려준다.
 */
final class ChangeStatementParser {

    /**
     * @param captureFrom 사본 조회의 FROM 대상(원문 표기, 별칭 포함) — 락 문법은 기종마다 달라 오퍼레이터가 조립한다
     * @param captureTail 원문 WHERE(또는 ORDER BY·LIMIT) 이하, 없으면 빈 문자열
     */
    record Parsed(Kind kind, String table, String captureFrom, String captureTail, String reason) {
        boolean capturable() {
            return kind != Kind.UNCAPTURED;
        }

        /** 락 절이 없는 표준 모양 — 읽기 쉬운 검증·표시용 */
        String captureSql() {
            return captureFrom == null ? null : "SELECT * FROM " + captureFrom + (captureTail.isEmpty() ? "" : " " + captureTail);
        }
    }

    private enum Type { WORD, IDENT, STRING, NUMBER, PUNCT }

    private record Token(Type type, String text, int start, int end, int depth) {
        boolean word(String w) {
            return type == Type.WORD && text.equalsIgnoreCase(w);
        }

        boolean name() {
            return type == Type.WORD || type == Type.IDENT;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RETURNING ="RETURNING 절이 있는 변경은 결과 집합을 돌려줘 영향 행 수 대조 계약이 달라진다";
    private static final String MULTI_TABLE = "여러 테이블을 함께 바꾸는 문장은 행 사본을 한 테이블로 잡을 수 없다";

    private ChangeStatementParser() {
    }

    static Parsed parse(String statement) {
        String sql = trimTerminator(statement == null ? "" : statement);
        List<Token> tokens = tokenize(sql);
        if (tokens.isEmpty() || tokens.get(0).type() != Type.WORD) {
            return uncaptured("문장 유형을 알 수 없다");
        }
        String head = tokens.get(0).text().toLowerCase(Locale.ROOT);
        return switch (head) {
            case "update" -> update(sql, tokens);
            case "delete" -> delete(sql, tokens);
            case "insert" -> insert(sql, tokens);
            case "create", "alter", "drop", "rename", "comment" -> new Parsed(Kind.DDL, null, null, null, null);
            case "with" -> uncaptured("CTE와 함께 쓴 변경은 대상 행을 따로 뽑아낼 수 없다");
            case "replace", "merge", "upsert" -> uncaptured("행을 지우고 다시 쓰거나 합치는 문장은 전후 행 대응을 확정할 수 없다");
            default -> uncaptured(head.toUpperCase(Locale.ROOT) + " 문장은 행 사본 캡처 대상이 아니다");
        };
    }

    private static Parsed update(String sql, List<Token> t) {
        int i = skipWords(t, 1, "low_priority", "ignore", "only");
        int set = findTop(t, i, "set");
        if (i >= t.size() || set < 0) {
            return uncaptured("UPDATE의 대상 테이블이나 SET 절을 찾지 못했다");
        }
        if (spansMultipleTables(t, i, set)) {
            return uncaptured(MULTI_TABLE);
        }
        if (findTop(t, set + 1, "returning") >= 0) {
            return uncaptured(RETURNING);
        }
        int tail = firstTop(t, set + 1, "from", "where", "order", "limit");
        if (tail >= 0 && t.get(tail).word("from")) {
            return uncaptured("UPDATE ... FROM 조인은 " + MULTI_TABLE);
        }
        String target = sql.substring(t.get(i).start(), t.get(set).start()).strip();
        return new Parsed(Kind.UPDATE, tableName(sql, t, i), target, tail(sql, t, tail), null);
    }

    private static Parsed delete(String sql, List<Token> t) {
        int i = skipWords(t, 1, "low_priority", "quick", "ignore");
        if (i < t.size() && t.get(i).word("from")) {
            i = skipWords(t, i + 1, "only");
        } else if (findTop(t, i, "from") >= 0) {
            return uncaptured("DELETE t1 FROM ... 형태는 " + MULTI_TABLE);
        }
        if (i >= t.size()) {
            return uncaptured("삭제 대상 테이블을 찾지 못했다");
        }
        if (findTop(t, i, "returning") >= 0) {
            return uncaptured(RETURNING);
        }
        int tail = firstTop(t, i, "using", "where", "order", "limit");
        if (tail >= 0 && t.get(tail).word("using")) {
            return uncaptured("DELETE ... USING 조인은 " + MULTI_TABLE);
        }
        if (spansMultipleTables(t, i, tail >= 0 ? tail : t.size())) {
            return uncaptured(MULTI_TABLE);
        }
        String target = (tail >= 0 ? sql.substring(t.get(i).start(), t.get(tail).start()) : sql.substring(t.get(i).start()))
                .strip();
        return new Parsed(Kind.DELETE, tableName(sql, t, i), target, tail(sql, t, tail), null);
    }

    private static Parsed insert(String sql, List<Token> t) {
        int i = skipWords(t, 1, "low_priority", "delayed", "high_priority", "ignore");
        if (i < t.size() && (t.get(i).word("all") || t.get(i).word("first"))) {
            return uncaptured("다중 테이블 INSERT는 " + MULTI_TABLE);
        }
        if (i < t.size() && t.get(i).word("into")) {
            i++;
        }
        if (i >= t.size() || !t.get(i).name()) {
            return uncaptured("삽입 대상 테이블을 찾지 못했다");
        }
        if (findTop(t, i, "returning") >= 0) {
            return uncaptured(RETURNING);
        }
        for (int on = findTop(t, i, "on"); on >= 0; on = findTop(t, on + 1, "on")) {
            boolean duplicate = on + 1 < t.size() && t.get(on + 1).word("duplicate");
            boolean conflictUpdate = on + 1 < t.size() && t.get(on + 1).word("conflict") && findTop(t, on + 1, "update") >= 0;
            if (duplicate || conflictUpdate) {
                return uncaptured("upsert는 이미 있던 행이 어떻게 바뀌었는지 사본을 잡을 수 없다");
            }
        }
        return new Parsed(Kind.INSERT, tableName(sql, t, i), null, null, null);
    }

    /**
     * 원문 WHERE(또는 ORDER BY·LIMIT) 이하를 그대로 떼어 둔다 — 조건을 다시 쓰지 않아야 실제 변경 대상과 같은 행을 본다.
     * 끝은 마지막 토큰에서 자른다: 뒤에 붙은 줄 주석(-- ...)이 남으면 오퍼레이터가 덧붙이는 FOR UPDATE를 주석으로 삼킨다.
     */
    private static String tail(String sql, List<Token> t, int tail) {
        if (tail < 0) {
            return "";
        }
        int last = t.size() - 1;
        while (last > 0 && t.get(last).type() == Type.PUNCT && ";".equals(t.get(last).text())) {
            last--;
        }
        return sql.substring(t.get(tail).start(), t.get(last).end()).strip();
    }

    /** [스키마.]테이블 표기를 원문 그대로 — 인용 식별자도 보존한다(오퍼레이터가 대소문자 규칙을 적용한다). */
    private static String tableName(String sql, List<Token> t, int i) {
        int j = i;
        if (j < t.size() && t.get(j).name()) {
            j++;
            while (j + 1 < t.size() && t.get(j).type() == Type.PUNCT && ".".equals(t.get(j).text()) && t.get(j + 1).name()) {
                j += 2;
            }
        }
        return j == i ? null : sql.substring(t.get(i).start(), t.get(j - 1).end());
    }

    private static boolean spansMultipleTables(List<Token> t, int from, int to) {
        for (int k = from; k < to && k < t.size(); k++) {
            Token tok = t.get(k);
            if (tok.depth() == 0 && (tok.word("join") || (tok.type() == Type.PUNCT && ",".equals(tok.text())))) {
                return true;
            }
        }
        return false;
    }

    private static int skipWords(List<Token> t, int i, String... words) {
        int j = i;
        outer:
        while (j < t.size()) {
            for (String w : words) {
                if (t.get(j).word(w)) {
                    j++;
                    continue outer;
                }
            }
            break;
        }
        return j;
    }

    private static int findTop(List<Token> t, int from, String word) {
        for (int k = Math.max(0, from); k < t.size(); k++) {
            if (t.get(k).depth() == 0 && t.get(k).word(word)) {
                return k;
            }
        }
        return -1;
    }

    private static int firstTop(List<Token> t, int from, String... words) {
        for (int k = Math.max(0, from); k < t.size(); k++) {
            Token tok = t.get(k);
            if (tok.depth() != 0) {
                continue;
            }
            for (String w : words) {
                if (tok.word(w)) {
                    return k;
                }
            }
        }
        return -1;
    }

    private static Parsed uncaptured(String reason) {
        return new Parsed(Kind.UNCAPTURED, null, null, null, reason);
    }

    /**
     * MongoDB 명령 JSON — 컬렉션과 변경 모양만 가른다. 사본 조건은 오퍼레이터가 명령 안의 q에서 그대로 읽는다(다시 쓰지 않는다).
     * 한 명령에 문이 여럿이거나 upsert·findAndModify·bulkWrite처럼 바뀔 문서를 미리 확정할 수 없는 모양은 캡처하지 않는다.
     */
    static Parsed parseMongo(String json) {
        JsonNode command;
        try {
            command = JSON.readTree(json);
        } catch (Exception e) {
            return uncaptured("MongoDB 명령 JSON을 해석할 수 없다");
        }
        if (command == null || !command.isObject() || command.isEmpty()) {
            return uncaptured("명령 이름이 있는 JSON 객체가 아니다");
        }
        String name = command.fieldNames().next();
        String collection = command.get(name).asText();
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "update" -> singleSpec(command, "updates", Kind.UPDATE, collection);
            case "delete" -> singleSpec(command, "deletes", Kind.DELETE, collection);
            case "insert" -> command.path("documents").isArray() && !command.path("documents").isEmpty()
                    ? new Parsed(Kind.INSERT, collection, null, null, null)
                    : uncaptured("삽입할 문서가 없다");
            case "create", "createindexes", "dropindexes", "collmod", "renamecollection" -> new Parsed(Kind.DDL, collection, null, null, null);
            default -> uncaptured(name + " 명령은 바꿀 문서를 미리 확정하지 않아 사본 대응을 잡을 수 없다");
        };
    }

    private static Parsed singleSpec(JsonNode command, String field, Kind kind, String collection) {
        JsonNode specs = command.path(field);
        if (!specs.isArray() || specs.size() != 1) {
            return uncaptured("한 명령에 " + field + " 문이 하나가 아니면 문서 사본 대응을 확정할 수 없다. 문마다 티켓을 나눠 올려라");
        }
        JsonNode spec = specs.get(0);
        if (spec.path("upsert").asBoolean(false)) {
            return uncaptured("upsert는 없던 문서가 생길지 확정할 수 없어 사본을 잡지 않는다");
        }
        if (!spec.path("q").isObject()) {
            return uncaptured("조건(q)이 객체가 아니다");
        }
        return new Parsed(kind, collection, collection, "", null);
    }

    static String trimTerminator(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    /**
     * 최상위 키워드를 찾기 위한 최소 토크나이저 — 주석·문자열·인용 식별자 안의 단어와 괄호 안(서브쿼리)의 단어를
     * 최상위로 오인하지 않는 것만 책임진다. 문법 검증은 대상 DB가 한다.
     */
    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int depth = 0;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                int e = s.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else if (c == '\'') {
                int e = quoteEnd(s, i, '\'', true);
                out.add(new Token(Type.STRING, s.substring(i, e), i, e, depth));
                i = e;
            } else if (c == '"' || c == '`') {
                int e = quoteEnd(s, i, c, false);
                out.add(new Token(Type.IDENT, s.substring(i, e), i, e, depth));
                i = e;
            } else if (c == '$' && dollarTag(s, i) != null) {
                String tag = dollarTag(s, i);
                int close = s.indexOf(tag, i + tag.length());
                int e = close < 0 ? n : close + tag.length();
                out.add(new Token(Type.STRING, s.substring(i, e), i, e, depth));
                i = e;
            } else if (c == '(') {
                out.add(new Token(Type.PUNCT, "(", i, i + 1, depth));
                depth++;
                i++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                out.add(new Token(Type.PUNCT, ")", i, i + 1, depth));
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '$'
                        || s.charAt(j) == '#')) {
                    j++;
                }
                out.add(new Token(Type.WORD, s.substring(i, j), i, j, depth));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Token(Type.NUMBER, s.substring(i, j), i, j, depth));
                i = j;
            } else {
                out.add(new Token(Type.PUNCT, String.valueOf(c), i, i + 1, depth));
                i++;
            }
        }
        return out;
    }

    /** 따옴표 두 번은 이스케이프. 백슬래시 이스케이프는 MySQL 기본값을 따른다 — 틀려도 행 수 대조가 잡는다. */
    private static int quoteEnd(String s, int start, char quote, boolean backslash) {
        int j = start + 1;
        while (j < s.length()) {
            char ch = s.charAt(j);
            if (backslash && ch == '\\') {
                j += 2;
            } else if (ch == quote) {
                if (j + 1 < s.length() && s.charAt(j + 1) == quote) {
                    j += 2;
                } else {
                    return j + 1;
                }
            } else {
                j++;
            }
        }
        return s.length();
    }

    /** PostgreSQL 달러 인용($$ ... $$, $tag$ ... $tag$)의 여는 태그 — 아니면 null */
    private static String dollarTag(String s, int i) {
        int j = i + 1;
        while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        if (j < s.length() && s.charAt(j) == '$' && (j == i + 1 || !Character.isDigit(s.charAt(i + 1)))) {
            return s.substring(i, j + 1);
        }
        return null;
    }
}
