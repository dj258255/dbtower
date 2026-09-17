package io.dbtower.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dbtower.registry.DbmsType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실행계획 마스킹 — AI로 계획을 내보내기 직전, <b>조건을 담은 자리 안에서만</b> 값을 가린다.
 *
 * <p>왜 계획도 가려야 하는가: {@link QueryMasker}는 SQL 문장만 가리는데, 옵티마이저는 조건 값을 계획에
 * 그대로 찍는다(docs/experiments/ai-masking-tradeoff.md 표 1 — SQL만 가려도 프롬프트에 41개 중 19개가 남았다).
 * 문장을 가려 놓고 계획을 원문으로 보내면 "값을 AI에 보내지 않는다"는 약속이 절반만 지켜진다.
 *
 * <p>왜 계획 전체가 아니라 키 한정인가: 계획의 {@code Plan Rows}·{@code filtered}·{@code Total Cost}는
 * 진단의 핵심 재료다. 실제로 L9(좁은 범위) 사례는 값을 다 가려도 계획에 남은 {@code filtered: 33.33}·
 * {@code rows_examined_per_scan: 200}만으로 세 조건 모두 같은 결론이 나왔다. 계획의 숫자를 통째로 가리면
 * 민감정보는 줄지만 진단이 죽는다. 그래서 조건을 담은 키(PG {@code Filter}·{@code Index Cond},
 * MySQL {@code attached_condition}·{@code ranges}, Oracle Predicate Information 절,
 * MSSQL {@code ScalarString}·{@code ConstValue}, Mongo {@code filter}·{@code parsedQuery}) 안에서만 가린다.
 *
 * <p>왜 앞뒤 {@code %}를 남기는가: 앞 와일드카드는 인덱스를 못 쓰는 이유 그 자체다. {@code '%@gmail.com'}을
 * {@code '?'}로 지우면 진단이 사라지므로 {@code '%?'}로 자리만 남긴다(E2' 조건 D).
 *
 * <p>Mongo는 SQL 스캐너를 쓸 수 없다 — 계획이 JSON이고 {@code "stage": "COLLSCAN"}처럼 <b>값 자체가
 * 계획의 모양</b>인 자리가 섞여 있다. 값을 통째로 가리면 계획이 사라지므로 조건 서브트리의 잎만 가린다.
 */
@Component
public class PlanMasker {

    /** PostgreSQL EXPLAIN (FORMAT JSON)에서 조건식이 담기는 키. 비용·행수 키는 일부러 넣지 않는다. */
    private static final Set<String> PG_KEYS = Set.of(
            "Filter", "Index Cond", "Recheck Cond", "Hash Cond", "Join Filter", "Merge Cond",
            "TID Cond", "One-Time Filter", "Table Filter", "Cache Key", "Conflict Filter",
            "Repeatable Seed", "Function Call");

    /** MySQL EXPLAIN FORMAT=JSON에서 조건식이 담기는 키. {@code ranges}는 문자열 배열이다. */
    private static final Set<String> MYSQL_KEYS = Set.of(
            "attached_condition", "index_condition", "pushed_index_condition", "ranges",
            "pushed_condition", "having_condition", "group_condition");

    /** MongoDB explain에서 조건이 담기는 키 — 이 서브트리의 잎만 가린다. */
    private static final Set<String> MONGO_KEYS = Set.of("filter", "parsedQuery", "indexBounds");

    /** MSSQL 실행계획 XML에서 값이 실리는 속성. */
    private static final Pattern MSSQL_ATTR =
            Pattern.compile("(ScalarString|ConstValue)=\"([^\"]*)\"");

    /** Oracle DBMS_XPLAN의 조건 절 — 이 헤더 아래 줄에만 값이 찍힌다. */
    private static final String ORACLE_PREDICATE_HEADER = "Predicate Information";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final boolean maskAiPlan;

    public PlanMasker(@Value("${dbtower.masking.enabled:true}") boolean enabled,
                      @Value("${dbtower.masking.mask-ai-plan:false}") boolean maskAiPlan) {
        this.enabled = enabled;
        this.maskAiPlan = maskAiPlan;
    }

    /**
     * AI 프롬프트 전용 — {@code enabled}와 {@code mask-ai-plan}이 둘 다 켜져 있을 때만 가린다.
     * 기본이 꺼진 이유는 {@link QueryMasker#applyForAiPrompt}와 같다: 가림은 진단 정확도와의 거래라
     * 명시적 선택으로 둔다. 다만 SQL 가림만 켜고 이걸 끄면 값이 계획을 타고 나간다(위 주석).
     */
    public String applyForAiPrompt(DbmsType type, String plan) {
        return (enabled && maskAiPlan) ? maskPlan(type, plan) : plan;
    }

    /** SQL 가림을 켰는데 계획 가림이 꺼져 있는지 — 호출자가 그 사실을 사람에게 알릴 수 있게 한다. */
    public boolean maskAiPlan() {
        return maskAiPlan;
    }

    /** 순수 알고리즘 — Spring 없이도 테스트 가능하도록 static. */
    public static String maskPlan(DbmsType type, String plan) {
        if (plan == null || plan.isBlank()) {
            return plan;
        }
        return switch (type) {
            case POSTGRESQL -> maskJson(plan, PG_KEYS, false);
            case MYSQL -> maskJson(plan, MYSQL_KEYS, false);
            case MONGODB -> maskJson(plan, MONGO_KEYS, true);
            case MSSQL -> maskMssql(plan);
            case ORACLE -> maskOracle(plan);
        };
    }

    /**
     * JSON 계획을 훑어 조건 키의 값만 가린다. {@code leafValues}면 그 키 아래 서브트리의 잎을 모두 가리고
     * (Mongo), 아니면 그 키의 문자열 값을 SQL 조건식으로 보고 리터럴만 가린다(PG·MySQL).
     *
     * <p>파싱이 안 되면 원문을 돌려주지 않고 형식 무관 가림으로 떨어진다 — 계획 형식이 바뀌었을 때
     * 조용히 원문을 내보내면 가림 설정이 거짓이 된다(fail-closed).
     */
    private static String maskJson(String plan, Set<String> keys, boolean leafValues) {
        try {
            JsonNode root = MAPPER.readTree(plan);
            walk(root, keys, leafValues, false);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return maskEveryQuotedLiteral(plan);
        }
    }

    private static void walk(JsonNode node, Set<String> keys, boolean leafValues, boolean inside) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                boolean hit = inside || keys.contains(e.getKey());
                JsonNode child = e.getValue();
                if (hit && child.isValueNode()) {
                    obj.set(e.getKey(), maskedValue(child, leafValues));
                } else {
                    // 배열은 조건 키가 값을 여러 개 싣는 형태다(MySQL ranges) — 잎 모드가 아니어도 안으로 들어간다
                    walk(child, keys, leafValues, hit && (leafValues || child.isArray()));
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (inside && child.isValueNode()) {
                    arr.set(i, maskedValue(child, leafValues));
                } else {
                    walk(child, keys, leafValues, inside);
                }
            }
        }
    }

    /**
     * 조건 자리의 값 하나. {@code leafValues}면 값 자체가 민감정보이므로 통째로 가리고,
     * 아니면 SQL 조건식이므로 스캐너에 맡겨 식별자·연산자는 남긴다.
     */
    private static JsonNode maskedValue(JsonNode value, boolean leafValues) {
        if (!leafValues && value.isTextual()) {
            return MAPPER.getNodeFactory().textNode(QueryMasker.maskLiterals(value.asText(), true));
        }
        if (value.isTextual()) {
            return MAPPER.getNodeFactory().textNode(wildcardShape(value.asText()));
        }
        if (value.isNumber()) {
            return MAPPER.getNodeFactory().textNode("?");
        }
        if (value.isBoolean() || value.isNull()) {
            return value;   // true/false·null은 값이 아니라 조건의 모양이다
        }
        return MAPPER.getNodeFactory().textNode("?");
    }

    /** 문자열 값에서 값만 지우고 와일드카드·정규식 앵커 자리는 남긴다 — {@code "@gmail.com$"} -> {@code "?$"}. */
    private static String wildcardShape(String body) {
        String lead = body.startsWith("%") ? "%" : body.startsWith("^") ? "^" : "";
        String trail = body.length() > 1 && body.endsWith("%") ? "%"
                : body.length() > 1 && body.endsWith("$") ? "$" : "";
        return lead + "?" + trail;
    }

    /** MSSQL XML — 값이 실리는 속성만 골라 그 안의 리터럴을 가린다. 연산자·컬럼 참조는 남는다. */
    private static String maskMssql(String plan) {
        Matcher m = MSSQL_ATTR.matcher(plan);
        StringBuilder out = new StringBuilder(plan.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(
                    m.group(1) + "=\"" + QueryMasker.maskLiterals(m.group(2), true) + "\""));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Oracle DBMS_XPLAN — "Predicate Information" 헤더 아래 줄만 가린다. 위쪽 표(Operation·Rows·Cost)는
     * 값이 없고 진단의 재료라 건드리지 않는다.
     */
    private static String maskOracle(String plan) {
        StringBuilder out = new StringBuilder(plan.length());
        boolean inPredicates = false;
        for (String line : plan.split("\n", -1)) {
            if (line.startsWith(ORACLE_PREDICATE_HEADER)) {
                inPredicates = true;
            }
            out.append(inPredicates ? maskOraclePredicateLine(line) : line).append('\n');
        }
        if (!plan.endsWith("\n") && out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * {@code "   2 - access(\"ID\">=99000)"} 한 줄. 왼쪽의 연산 id는 위 표와 줄을 잇는 번호라 남기고
     * {@code " - "} 뒤만 가린다 — id까지 {@code ?}가 되면 어느 연산의 조건인지 읽을 수 없다.
     */
    private static String maskOraclePredicateLine(String line) {
        int sep = line.indexOf(" - ");
        if (sep < 0) {
            return QueryMasker.maskQuotedLiterals(line);
        }
        return line.substring(0, sep + 3) + QueryMasker.maskLiterals(line.substring(sep + 3), true);
    }

    /**
     * 형식을 알아보지 못했을 때의 마지막 방어 — 작은따옴표 문자열만 가리고 숫자는 남긴다.
     * 숫자를 남기는 이유는 계획의 행수·비용이 대부분 숫자이기 때문이다(가리면 진단이 죽는다).
     *
     * <p>여기서 {@link QueryMasker}의 스캐너를 쓰지 않는다 — 그 스캐너는 큰따옴표를 SQL 식별자로 보고
     * 통째로 보존하는데, 깨진 JSON에서는 계획 전체가 큰따옴표 안이라 값이 그대로 나갔다.
     */
    private static String maskEveryQuotedLiteral(String plan) {
        StringBuilder out = new StringBuilder(plan.length());
        int i = 0;
        int n = plan.length();
        while (i < n) {
            char c = plan.charAt(i);
            if (c != '\'') {
                out.append(c);
                i++;
                continue;
            }
            int close = plan.indexOf('\'', i + 1);
            if (close < 0) {
                out.append("'?");   // 닫히지 않은 따옴표 — 남은 전부가 값일 수 있으니 버린다
                return out.toString();
            }
            out.append(wildcardQuoted(plan.substring(i + 1, close)));
            i = close + 1;
        }
        return out.toString();
    }

    private static String wildcardQuoted(String body) {
        return "'" + wildcardShape(body) + "'";
    }
}
