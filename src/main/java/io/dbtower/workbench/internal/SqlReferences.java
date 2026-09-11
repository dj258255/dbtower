package io.dbtower.workbench.internal;

import io.dbtower.operator.SqlCanonical;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문장이 참조하는 테이블 이름을 뽑는다 — AI가 제안한 SQL이 스키마에 없는 테이블을 쓰는지(환각) 결정론적으로 확인하는 데 쓴다.
 *
 * <p>파서가 아니라 FROM·JOIN·UPDATE·INTO 뒤의 이름을 보는 휴리스틱이다. 주석·문자열은 canonical로 지운 뒤 본다.
 * CTE 이름(WITH x AS)은 테이블이 아니므로 뺀다. 목적이 "없는 테이블을 알린다"라서, 놓치는 쪽(알림 누락)보다
 * 오탐(있는 테이블을 없다고 알림)이 사람에게 더 거슬리므로 스키마 한정자는 마지막 부분만 비교한다.
 */
final class SqlReferences {

    private static final Pattern TABLE_AFTER = Pattern.compile(
            "(?i)\\b(?:from|join|update|into)\\s+([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)?)");

    private static final Pattern CTE_NAME = Pattern.compile("(?i)(?:\\bwith|,)\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s+as\\s*\\(");

    private SqlReferences() {
    }

    static Set<String> tables(String sql) {
        Set<String> out = new LinkedHashSet<>();
        if (sql == null || sql.isBlank() || sql.strip().startsWith("{")) {
            return out;
        }
        String canonical = SqlCanonical.canonical(sql);
        Set<String> cteNames = new LinkedHashSet<>();
        Matcher cte = CTE_NAME.matcher(canonical);
        while (cte.find()) {
            cteNames.add(cte.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher m = TABLE_AFTER.matcher(canonical);
        while (m.find()) {
            String name = m.group(1);
            String last = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (!cteNames.contains(last)) {
                out.add(last);
            }
        }
        return out;
    }
}
