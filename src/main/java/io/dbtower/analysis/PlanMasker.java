package io.dbtower.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dbtower.registry.DbmsType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
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

    /**
     * MSSQL 실행계획 XML에서 값이 실리는 속성.
     *
     * <p>{@code StatementText}가 함께 들어 있다 — 실제 showplan 캡처로 바꿔 보고 알았다. 이 속성에는
     * 사용자가 친 SQL이 통째로 들어가므로 술어만 가리면 바로 옆에 원문이 남는다. 합성 픽스처에는
     * 이 속성이 없어서 보이지 않던 구멍이다.
     */
    private static final Pattern MSSQL_ATTR =
            Pattern.compile("(ScalarString|ConstValue|StatementText)=\"([^\"]*)\"");

    /** Oracle DBMS_XPLAN의 조건 절 — 이 헤더 아래 줄에만 값이 찍힌다. */
    private static final String ORACLE_PREDICATE_HEADER = "Predicate Information";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final boolean maskAiPlan;
    private final AiMaskLevel level;
    private final Clock clock;

    @Autowired
    public PlanMasker(@Value("${dbtower.masking.enabled:true}") boolean enabled,
                      @Value("${dbtower.masking.mask-ai-plan:false}") boolean maskAiPlan,
                      @Value("${dbtower.masking.ai-plan-level:STRUCTURE}") AiMaskLevel level) {
        this(enabled, maskAiPlan, level, Clock.systemDefaultZone());
    }

    /** 날짜 거리를 재려면 "지금"이 필요하다 — 테스트가 시각을 고정할 수 있게 시계를 받는다. */
    PlanMasker(boolean enabled, boolean maskAiPlan, AiMaskLevel level, Clock clock) {
        this.enabled = enabled;
        this.maskAiPlan = maskAiPlan;
        this.level = level == null ? AiMaskLevel.STRUCTURE : level;
        this.clock = clock;
    }

    /** 기존 두 인자 생성자 — 수준을 적지 않으면 값의 모양을 남기는 {@link AiMaskLevel#STRUCTURE}다. */
    public PlanMasker(boolean enabled, boolean maskAiPlan) {
        this(enabled, maskAiPlan, AiMaskLevel.STRUCTURE, Clock.systemDefaultZone());
    }

    /**
     * AI 프롬프트 전용 — {@code enabled}와 {@code mask-ai-plan}이 둘 다 켜져 있을 때만 가린다.
     * 기본이 꺼진 이유는 {@link QueryMasker#applyForAiPrompt}와 같다: 가림은 진단 정확도와의 거래라
     * 명시적 선택으로 둔다. 다만 SQL 가림만 켜고 이걸 끄면 값이 계획을 타고 나간다(위 주석).
     */
    public String applyForAiPrompt(DbmsType type, String plan) {
        return masking() ? maskPlan(type, plan, level, clock) : plan;
    }

    private boolean masking() {
        return enabled && maskAiPlan && level != AiMaskLevel.NONE;
    }

    /** 지금 걸려 있는 가림 수준 — 실험과 화면이 "무엇으로 가렸나"를 함께 남길 수 있게 공개한다. */
    public AiMaskLevel level() {
        return level;
    }

    /** SQL 가림을 켰는데 계획 가림이 꺼져 있는지 — 호출자가 그 사실을 사람에게 알릴 수 있게 한다. */
    public boolean maskAiPlan() {
        return maskAiPlan;
    }

    /**
     * 기종을 모르는 자리에서 쓰는 가림 — MCP 진단처럼 도구 결과 문자열만 손에 쥔 경우다.
     *
     * <p>형식을 못 가리면 키 한정 가림을 걸 수 없으니 따옴표 문자열을 전부 지우는 쪽으로 떨어진다.
     * 진단 재료인 행수·비용은 숫자라 남는다. 원문을 그대로 돌려주지 않는 이유는
     * {@link #maskJson} 실패 경로와 같다 — 조용히 통과시키면 가림 설정이 거짓이 된다.
     */
    public String applyForAiPrompt(String plan) {
        if (!masking() || plan == null || plan.isBlank()) {
            return plan;
        }
        return maskEveryQuotedLiteral(plan, level, clock);
    }

    /** 순수 알고리즘 — Spring 없이도 테스트 가능하도록 static. */
    public static String maskPlan(DbmsType type, String plan) {
        return maskPlan(type, plan, AiMaskLevel.STRUCTURE, Clock.systemDefaultZone());
    }

    /** 수준을 주는 쪽 — {@link AiMaskLevel#STRUCTURE}는 값의 모양을 남기고 {@code FULL}은 전부 지운다. */
    public static String maskPlan(DbmsType type, String plan, AiMaskLevel level, Clock clock) {
        if (plan == null || plan.isBlank() || level == AiMaskLevel.NONE) {
            return plan;
        }
        return switch (type) {
            case POSTGRESQL -> maskJson(plan, PG_KEYS, false, level, clock);
            case MYSQL -> maskJson(plan, MYSQL_KEYS, false, level, clock);
            case MONGODB -> maskJson(plan, MONGO_KEYS, true, level, clock);
            case MSSQL -> maskMssql(plan, level, clock);
            case ORACLE -> maskOracle(plan, level, clock);
        };
    }

    /**
     * JSON 계획을 훑어 조건 키의 값만 가린다. {@code leafValues}면 그 키 아래 서브트리의 잎을 모두 가리고
     * (Mongo), 아니면 그 키의 문자열 값을 SQL 조건식으로 보고 리터럴만 가린다(PG·MySQL).
     *
     * <p>파싱이 안 되면 원문을 돌려주지 않고 형식 무관 가림으로 떨어진다 — 계획 형식이 바뀌었을 때
     * 조용히 원문을 내보내면 가림 설정이 거짓이 된다(fail-closed).
     */
    private static String maskJson(String plan, Set<String> keys, boolean leafValues,
                                   AiMaskLevel level, Clock clock) {
        try {
            JsonNode root = MAPPER.readTree(plan);
            walk(root, keys, leafValues, false, level, clock);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return maskEveryQuotedLiteral(plan, level, clock);
        }
    }

    private static void walk(JsonNode node, Set<String> keys, boolean leafValues, boolean inside,
                             AiMaskLevel level, Clock clock) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                boolean hit = inside || keys.contains(e.getKey());
                JsonNode child = e.getValue();
                if (hit && child.isValueNode()) {
                    obj.set(e.getKey(), maskedValue(child, leafValues, level, clock));
                } else {
                    // 배열은 조건 키가 값을 여러 개 싣는 형태다(MySQL ranges) — 잎 모드가 아니어도 안으로 들어간다
                    walk(child, keys, leafValues, hit && (leafValues || child.isArray()), level, clock);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (inside && child.isValueNode()) {
                    arr.set(i, maskedValue(child, leafValues, level, clock));
                } else {
                    walk(child, keys, leafValues, inside, level, clock);
                }
            }
        }
    }

    /**
     * 조건 자리의 값 하나. {@code leafValues}면 값 자체가 민감정보이므로 통째로 가리고,
     * 아니면 SQL 조건식이므로 스캐너에 맡겨 식별자·연산자는 남긴다.
     */
    private static JsonNode maskedValue(JsonNode value, boolean leafValues, AiMaskLevel level, Clock clock) {
        if (!leafValues && value.isTextual()) {
            return MAPPER.getNodeFactory().textNode(QueryMasker.maskPlanLiterals(value.asText(), level, clock));
        }
        if (value.isTextual()) {
            return MAPPER.getNodeFactory().textNode(MaskShape.body(value.asText(), level, clock));
        }
        if (value.isNumber()) {
            return MAPPER.getNodeFactory().textNode(MaskShape.number(value.asText(), level));
        }
        if (value.isBoolean() || value.isNull()) {
            return value;   // true/false·null은 값이 아니라 조건의 모양이다
        }
        return MAPPER.getNodeFactory().textNode("?");
    }

    /** MSSQL XML — 값이 실리는 속성만 골라 그 안의 리터럴을 가린다. 연산자·컬럼 참조는 남는다. */
    private static String maskMssql(String plan, AiMaskLevel level, Clock clock) {
        Matcher m = MSSQL_ATTR.matcher(plan);
        StringBuilder out = new StringBuilder(plan.length());
        while (m.find()) {
            // 속성값은 XML로 이스케이프돼 있다(&apos;) — 풀어서 스캐너에 넘기지 않으면 따옴표를 못 본다
            String masked = escapeXml(
                    QueryMasker.maskPlanLiterals(unescapeXml(m.group(2)), level, clock));
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + "=\"" + masked + "\""));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String unescapeXml(String s) {
        return s.replace("&apos;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * Oracle DBMS_XPLAN — "Predicate Information" 헤더 아래 줄만 가린다. 위쪽 표(Operation·Rows·Cost)는
     * 값이 없고 진단의 재료라 건드리지 않는다.
     */
    private static String maskOracle(String plan, AiMaskLevel level, Clock clock) {
        StringBuilder out = new StringBuilder(plan.length());
        boolean inPredicates = false;
        for (String line : plan.split("\n", -1)) {
            if (line.startsWith(ORACLE_PREDICATE_HEADER)) {
                inPredicates = true;
            }
            out.append(inPredicates ? maskOraclePredicateLine(line, level, clock) : line).append('\n');
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
    private static String maskOraclePredicateLine(String line, AiMaskLevel level, Clock clock) {
        int sep = line.indexOf(" - ");
        if (sep < 0) {
            return QueryMasker.maskQuotedLiterals(line);
        }
        return line.substring(0, sep + 3) + QueryMasker.maskPlanLiterals(line.substring(sep + 3), level, clock);
    }

    /**
     * 형식을 알아보지 못했을 때의 마지막 방어 — 작은따옴표 문자열만 가리고 숫자는 남긴다.
     * 숫자를 남기는 이유는 계획의 행수·비용이 대부분 숫자이기 때문이다(가리면 진단이 죽는다).
     *
     * <p>여기서 {@link QueryMasker}의 스캐너를 쓰지 않는다 — 그 스캐너는 큰따옴표를 SQL 식별자로 보고
     * 통째로 보존하는데, 깨진 JSON에서는 계획 전체가 큰따옴표 안이라 값이 그대로 나갔다.
     */
    private static String maskEveryQuotedLiteral(String plan, AiMaskLevel level, Clock clock) {
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
            out.append("'").append(MaskShape.body(plan.substring(i + 1, close), level, clock)).append("'");
            i = close + 1;
        }
        return out.toString();
    }

}
