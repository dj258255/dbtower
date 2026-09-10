package io.dbtower.workbench.internal;

import io.dbtower.operator.model.ResultColumn;
import io.dbtower.workbench.internal.domain.MaskingStrategy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 결과 마스킹 — SQL을 고치지 않고, 결과를 만드는 단계에서 컬럼 이름으로 값을 가린다.
 *
 * <p>결과 열 이름, 드라이버가 준 원래 컬럼명, 문장에서 뽑은 {@code 원래열 AS 별칭} 쌍을 모두 규칙에 대 본다.
 * 드라이버에만 기대면 안 된다는 것이 실측이었다: pgjdbc는 원래 컬럼명 자리에 별칭을 돌려줘 {@code SELECT email AS e}가
 * 원문 그대로 나갔다(VERIFICATION 128절). 별칭 추출은 과하게 가리는 쪽으로 틀리게 짠다.
 *
 * <p>그래도 표현식({@code CONCAT(email, '')})은 못 잡는다. 본 방어선은 콘솔 계정의 컬럼 단위 권한이고,
 * 이 계층은 "실수로 보는 것"을 줄이는 두 번째 겹이다.
 */
public final class ResultMasker {

    public record Policy(String columnPattern, MaskingStrategy strategy) {
    }

    public record Masked(List<List<Object>> rows, List<String> maskedColumns) {
    }

    /** [한정자.]원래열 [AS] 별칭 — 인용된 별칭("e", `e`, [e])도 받는다. 키워드 쌍(FROM customers)이 섞여도 가리는 쪽으로만 틀린다. */
    private static final Pattern ALIAS = Pattern.compile(
            "(?i)(?:[A-Za-z_][A-Za-z0-9_$]*\\.)?([A-Za-z_][A-Za-z0-9_$]*)\\s+(?:as\\s+)?[\"`\\[]?([A-Za-z_][A-Za-z0-9_$]*)[\"`\\]]?");

    private ResultMasker() {
    }

    public static Masked apply(List<ResultColumn> columns, List<List<Object>> rows, List<Policy> policies) {
        return apply(columns, rows, policies, null);
    }

    public static Masked apply(List<ResultColumn> columns, List<List<Object>> rows, List<Policy> policies,
                               String statement) {
        Map<String, Set<String>> aliasSources = aliasSources(statement);
        MaskingStrategy[] byColumn = new MaskingStrategy[columns.size()];
        List<String> masked = new ArrayList<>();
        List<Pattern> patterns = policies.stream().map(p -> glob(p.columnPattern())).toList();
        for (int c = 0; c < columns.size(); c++) {
            ResultColumn column = columns.get(c);
            for (int p = 0; p < policies.size(); p++) {
                Pattern pattern = patterns.get(p);
                if (matches(pattern, column.name()) || matches(pattern, column.baseName())
                        || aliasSources.getOrDefault(lower(column.name()), Set.of()).stream()
                        .anyMatch(source -> matches(pattern, source))) {
                    byColumn[c] = policies.get(p).strategy();
                    masked.add(column.name());
                    break;
                }
            }
        }
        if (masked.isEmpty()) {
            return new Masked(rows, List.of());
        }
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> copy = new ArrayList<>(row);
            for (int c = 0; c < byColumn.length; c++) {
                if (byColumn[c] != null) {
                    copy.set(c, mask(copy.get(c), byColumn[c]));
                }
            }
            out.add(copy);
        }
        return new Masked(out, List.copyOf(masked));
    }

    static Object mask(Object value, MaskingStrategy strategy) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return switch (strategy) {
            case FULL -> "****";
            case PARTIAL -> partial(text);
            case HASH -> "h:" + sha256(text).substring(0, 12);
        };
    }

    /** 코드포인트 단위로 자른다 — 한글 이름("홍길동")이 바이트 중간에서 깨지지 않게. */
    static String partial(String text) {
        int[] cps = text.codePoints().toArray();
        int n = cps.length;
        if (n <= 1) {
            return "*";
        }
        int head = n <= 6 ? 1 : 2;
        int tail = n <= 6 ? 0 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.appendCodePoint(i < head || i >= n - tail ? cps[i] : '*');
        }
        return sb.toString();
    }

    /**
     * 별칭(소문자) -> 원래 컬럼 후보들(소문자). 매치를 겹쳐 훑는다: {@code SELECT email AS e}에서 첫 매치가
     * "SELECT email"을 먹으면 다음 매치가 "email AS e"를 놓치므로, 다음 탐색을 별칭 토큰 자리에서 다시 시작한다.
     * 같은 별칭의 후보는 모두 남긴다 — 규칙에 대 보는 입력이 늘 뿐이라 가리는 쪽으로만 틀린다.
     */
    static Map<String, Set<String>> aliasSources(String statement) {
        Map<String, Set<String>> out = new HashMap<>();
        if (statement == null) {
            return out;
        }
        Matcher m = ALIAS.matcher(statement);
        int from = 0;
        while (from < statement.length() && m.find(from)) {
            out.computeIfAbsent(lower(m.group(2)), k -> new HashSet<>()).add(lower(m.group(1)));
            from = m.start(2);
        }
        return out;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static boolean matches(Pattern pattern, String columnName) {
        return columnName != null && pattern.matcher(columnName.toLowerCase(Locale.ROOT)).matches();
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.toLowerCase(Locale.ROOT).split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없는 JVM", e);
        }
    }
}
