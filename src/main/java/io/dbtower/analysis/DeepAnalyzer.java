package io.dbtower.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.analysis.DeepDiagnosis.CardinalityGap;
import io.dbtower.analysis.DeepDiagnosis.RootCause;
import io.dbtower.operator.ColumnSchema;
import io.dbtower.operator.IndexSchema;
import io.dbtower.operator.SchemaSnapshot;
import io.dbtower.operator.TableSchema;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 심층 원인 진단기 (D9) — 실제 실행 계획을 기종별로 파싱해 두 축의 근본원인을 짚는다.
 *
 * 1) 카디널리티 오추정: 추정 vs 실제 행수가 10배+ 갈라지는 <b>가장 아래(최하위) 노드</b>를 지목한다.
 *    MySQL·PostgreSQL의 노드 실제 행수는 loops당 평균이라 총량은 loops를 곱해 환산한다(오독 함정).
 *    Oracle의 A-Rows는 이미 누적 총량이라 곱하지 않는다.
 * 2) 인덱스 무력화 근본원인 5종: 암시적 형변환·컬럼에 함수/표현식·앞 와일드카드·복합 선두 누락·통계 노후.
 *    SQL 술어와 스키마(컬럼 타입·인덱스 선두)를 대조하고, 플랜의 경고(CONVERT_IMPLICIT)도 신호로 쓴다.
 *
 * RuleBasedAnalyzer가 증상(풀스캔·정렬)을 잡는다면, 여기서부터는 "왜 그 플랜이 골라졌나"의 원인이다.
 * 판정 스펙은 docs/ai-analysis-rules.md "심층 원인 규칙 (D9)" 절. 근거가 없으면 원인을 지어내지 않는다.
 */
@Component
public class DeepAnalyzer {

    /** 추정 vs 실제가 이 배수 이상 벌어지면 카디널리티 오추정으로 지목한다(문서: 10배+). */
    private static final double GAP_THRESHOLD = 10.0;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LOOPS_NOTE =
            "MySQL·PostgreSQL의 노드별 실제 행수는 loops당 평균이라 총량은 loops를 곱해 환산했다(actualRows는 총량 기준).";

    // WHERE 절 추출(GROUP/ORDER/HAVING/LIMIT/FETCH/; 전까지) — 진단용 휴리스틱이라 중첩 괄호까진 보지 않는다
    private static final Pattern WHERE = Pattern.compile(
            "(?is)\\bwhere\\b(.*?)(?:\\bgroup\\s+by\\b|\\border\\s+by\\b|\\bhaving\\b|\\blimit\\b|\\bfetch\\b|;|$)");
    // 비교 술어: lhs op rhs (op는 =, <>, !=, >=, <=, >, <, LIKE)
    private static final Pattern COMPARE = Pattern.compile(
            "(?is)^(.+?)\\s*(<>|!=|>=|<=|=|>|<|\\blike\\b)\\s*(.+)$");
    // lhs가 함수/표현식 안에 컬럼을 감싼 형태: FUNC( 또는 col +-*/ 연산
    private static final Pattern FUNC_ON_COL = Pattern.compile("(?is)^[a-z_][a-z0-9_]*\\s*\\(");
    private static final Pattern ARITH_ON_COL = Pattern.compile("(?is)^[a-z_][a-z0-9_.]*\\s*[+\\-*/]");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");

    // MySQL EXPLAIN ANALYZE(TREE): (cost=.. rows=EST) ... (actual time=.. rows=ACT loops=L)
    private static final Pattern MY_EST = Pattern.compile("\\(cost=[0-9.eE+]+ rows=(\\d+)\\)");
    private static final Pattern MY_ACT = Pattern.compile(
            "actual time=[0-9.eE+]+\\.\\.[0-9.eE+]+ rows=(\\d+) loops=(\\d+)");

    /**
     * 실제 실행 계획을 근본원인 진단으로 바꾼다.
     *
     * @param type   기종
     * @param query  진단 대상 쿼리(JDBC는 SQL, MongoDB는 명령 JSON)
     * @param plan   operator.explainAnalyze()가 돌려준 실제 실행 계획 원문
     * @param schema describeSchema() 스냅샷(컬럼 타입·인덱스 선두 판정용). 못 가져왔으면 null 허용
     */
    public DeepDiagnosis diagnose(DbmsType type, String query, String plan, SchemaSnapshot schema) {
        List<String> notes = new ArrayList<>();
        if (schema == null && type != DbmsType.MONGODB) {
            notes.add("스키마 스냅샷 미가용 — 타입·인덱스 기반 근본원인(형변환·복합 선두 누락) 판정은 생략됨.");
        }
        CardinalityGap gap = switch (type) {
            case POSTGRESQL -> {
                notes.add(LOOPS_NOTE);
                yield parsePostgres(plan, notes);
            }
            case MYSQL -> {
                notes.add(LOOPS_NOTE);
                yield parseMysql(plan, notes);
            }
            case ORACLE -> parseOracle(plan, notes);
            case MSSQL -> parseMssql(plan, notes);
            case MONGODB -> parseMongo(plan, notes);
        };
        List<RootCause> causes = (type == DbmsType.MONGODB)
                ? mongoRootCauses(plan, gap)
                : sqlRootCauses(query, plan, schema, gap);
        return new DeepDiagnosis(plan, gap, causes, notes);
    }

    // ---------- 카디널리티 괴리: 최하위 노드 선택 ----------

    /** 최하위(가장 깊은) 우선, 같은 깊이면 배수 큰 것을 담는 후보 홀더. */
    private static final class GapPick {
        CardinalityGap gap;
        int depth = -1;

        void offer(int d, CardinalityGap g) {
            if (d > depth || (d == depth && gap != null && g.ratio() > gap.ratio())) {
                depth = d;
                gap = g;
            }
        }
    }

    private CardinalityGap parsePostgres(String plan, List<String> notes) {
        try {
            JsonNode root = MAPPER.readTree(plan);
            JsonNode planNode = root.isArray() ? root.get(0).get("Plan") : root.path("Plan");
            if (planNode == null || planNode.isMissingNode()) {
                return null;
            }
            GapPick pick = new GapPick();
            walkPg(planNode, 0, pick);
            return pick.gap;
        } catch (Exception e) {
            notes.add("PostgreSQL 실행계획 파싱 실패 — 카디널리티 괴리 판정 생략: " + e.getMessage());
            return null;
        }
    }

    private void walkPg(JsonNode node, int depth, GapPick pick) {
        if (node.has("Plan Rows") && node.has("Actual Rows")) {
            double estPerLoop = node.get("Plan Rows").asDouble();
            double actPerLoop = node.get("Actual Rows").asDouble();
            long loops = node.path("Actual Loops").asLong(1);
            double ratio = ratio(estPerLoop, actPerLoop);
            if (ratio >= GAP_THRESHOLD) {
                String label = node.path("Node Type").asText("?");
                String rel = node.path("Relation Name").asText("");
                if (!rel.isEmpty()) {
                    label += " on " + rel;
                }
                pick.offer(depth, new CardinalityGap(label,
                        Math.round(estPerLoop * loops), Math.round(actPerLoop * loops), loops, round2(ratio)));
            }
        }
        for (JsonNode child : node.path("Plans")) {
            walkPg(child, depth + 1, pick);
        }
    }

    private CardinalityGap parseMysql(String plan, List<String> notes) {
        if (plan == null || plan.isBlank()) {
            return null;
        }
        // JDBC는 실제 줄바꿈을 준다. 혹시 한 줄에 리터럴 \n로 뭉쳐 오면 그것으로도 나눈다.
        String[] lines = plan.split("\\r?\\n");
        if (lines.length == 1 && plan.contains("\\n")) {
            lines = plan.split("\\\\n");
        }
        GapPick pick = new GapPick();
        boolean parsedAny = false;
        for (String line : lines) {
            Matcher est = MY_EST.matcher(line);
            Matcher act = MY_ACT.matcher(line);
            if (!est.find() || !act.find()) {
                continue;
            }
            parsedAny = true;
            double estPerLoop = Double.parseDouble(est.group(1));
            double actPerLoop = Double.parseDouble(act.group(1));
            long loops = Long.parseLong(act.group(2));
            double ratio = ratio(estPerLoop, actPerLoop);
            if (ratio >= GAP_THRESHOLD) {
                pick.offer(indent(line), new CardinalityGap(mysqlLabel(line),
                        Math.round(estPerLoop * loops), Math.round(actPerLoop * loops), loops, round2(ratio)));
            }
        }
        if (!parsedAny) {
            notes.add("MySQL 실행계획에서 actual rows/loops를 파싱하지 못함 — EXPLAIN ANALYZE(TREE) 출력인지 확인.");
        }
        return pick.gap;
    }

    private CardinalityGap parseOracle(String plan, List<String> notes) {
        String[] lines = plan.split("\\r?\\n");
        int headerIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("E-Rows") && lines[i].contains("A-Rows")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) {
            notes.add("Oracle 계획에 E-Rows/A-Rows 컬럼이 없음 — gather_plan_statistics 미적용/권한 부족 가능.");
            return null;
        }
        String[] header = lines[headerIdx].split("\\|");
        int eIdx = -1;
        int aIdx = -1;
        int opIdx = -1;
        int nameIdx = -1;
        for (int c = 0; c < header.length; c++) {
            String h = header[c].trim();
            switch (h) {
                case "E-Rows" -> eIdx = c;
                case "A-Rows" -> aIdx = c;
                case "Operation" -> opIdx = c;
                case "Name" -> nameIdx = c;
                default -> { }
            }
        }
        if (eIdx < 0 || aIdx < 0) {
            return null;
        }
        GapPick pick = new GapPick();
        for (int i = headerIdx + 1; i < lines.length; i++) {
            String[] cols = lines[i].split("\\|");
            if (cols.length <= Math.max(eIdx, aIdx)) {
                continue;
            }
            Long est = parseOracleNum(cols[eIdx]);
            Long act = parseOracleNum(cols[aIdx]);
            if (est == null || act == null) {
                continue;
            }
            double ratio = ratio(est, act);
            if (ratio < GAP_THRESHOLD) {
                continue;
            }
            String op = opIdx >= 0 && opIdx < cols.length ? cols[opIdx] : "?";
            String nm = nameIdx >= 0 && nameIdx < cols.length ? cols[nameIdx].trim() : "";
            int depth = indent(op);
            String label = op.trim() + (nm.isEmpty() ? "" : " (" + nm + ")");
            // Oracle A-Rows는 이미 누적 총량 — loops=1로 표기(곱하지 않는다)
            pick.offer(depth, new CardinalityGap(label, est, act, 1, round2(ratio)));
        }
        return pick.gap;
    }

    private CardinalityGap parseMssql(String plan, List<String> notes) {
        if (plan == null || !plan.contains("ShowPlanXML")) {
            return null;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // XXE 방어 — 외부 엔티티/DTD 차단
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(plan)));
            NodeList relOps = doc.getElementsByTagName("RelOp");
            CardinalityGap best = null;
            for (int i = 0; i < relOps.getLength(); i++) {
                Element rel = (Element) relOps.item(i);
                if (!rel.hasAttribute("EstimateRows")) {
                    continue;
                }
                double est = parseD(rel.getAttribute("EstimateRows"));
                long act = sumOwnActualRows(rel); // 하위 RelOp 통계는 제외한 이 노드만의 실측
                if (act < 0) {
                    continue;
                }
                double ratio = ratio(est, act);
                if (ratio < GAP_THRESHOLD) {
                    continue;
                }
                // XML은 깊이 추적이 번거로워 배수 최대 노드를 지목한다(안내로 명시)
                if (best == null || ratio > best.ratio()) {
                    best = new CardinalityGap(rel.getAttribute("PhysicalOp"),
                            Math.round(est), act, 1, round2(ratio));
                }
            }
            if (best != null) {
                notes.add("SQL Server는 XML 깊이 대신 괴리 배수가 가장 큰 노드를 지목한다.");
            }
            return best;
        } catch (Exception e) {
            notes.add("SQL Server ShowPlanXML 파싱 실패 — 카디널리티 괴리 판정 생략: " + e.getMessage());
            return null;
        }
    }

    private long sumOwnActualRows(Element relOp) {
        long[] sum = {-1};
        collectActual(relOp, sum);
        return sum[0];
    }

    /** 이 RelOp 소유의 RunTimeCountersPerThread ActualRows만 합산(하위 RelOp는 그 노드 몫이라 재귀 중단). */
    private void collectActual(Node node, long[] sum) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = n.getNodeName();
            if ("RelOp".equals(name)) {
                continue; // 하위 노드 통계는 별도 노드 몫
            }
            if ("RunTimeCountersPerThread".equals(name)) {
                Element e = (Element) n;
                if (e.hasAttribute("ActualRows")) {
                    if (sum[0] < 0) {
                        sum[0] = 0;
                    }
                    sum[0] += Long.parseLong(e.getAttribute("ActualRows"));
                }
            }
            collectActual(n, sum);
        }
    }

    private CardinalityGap parseMongo(String plan, List<String> notes) {
        try {
            JsonNode es = MAPPER.readTree(plan).path("executionStats");
            if (es.isMissingNode()) {
                notes.add("MongoDB executionStats가 없음 — verbosity executionStats로 실행됐는지 확인.");
                return null;
            }
            long docs = es.path("totalDocsExamined").asLong(-1);
            long ret = es.path("nReturned").asLong(-1);
            long keys = es.path("totalKeysExamined").asLong(0);
            if (docs < 0 || ret < 0) {
                return null;
            }
            double ratio = ratio(Math.max(ret, 1), docs);
            notes.add("MongoDB는 추정 vs 실제 대신 docsExamined(" + docs + ") ÷ nReturned(" + ret
                    + ")로 스캔 낭비를 본다. totalKeysExamined=" + keys + ".");
            if (ratio < GAP_THRESHOLD) {
                return null;
            }
            // estimatedRows 자리에 nReturned, actualRows 자리에 docsExamined를 담아 스캔 낭비 배수를 표현한다
            return new CardinalityGap("docsExamined÷nReturned", ret, docs, 1, round2(ratio));
        } catch (Exception e) {
            notes.add("MongoDB executionStats 파싱 실패: " + e.getMessage());
            return null;
        }
    }

    // ---------- 근본원인 5종 ----------

    private List<RootCause> sqlRootCauses(String sql, String plan, SchemaSnapshot schema, CardinalityGap gap) {
        List<RootCause> causes = new ArrayList<>();

        // 플랜에 박힌 신호(기종이 직접 알려주는 경우) — SQL Server의 암시적 형변환 경고
        if (plan != null && plan.contains("CONVERT_IMPLICIT")) {
            causes.add(new RootCause("암시적 형변환",
                    "실행 계획에 CONVERT_IMPLICIT 경고",
                    "컬럼 값을 실행 시점에 변환하느라 인덱스 seek이 scan이 된다 — 비교값 타입을 컬럼과 맞춰라."));
        }

        Set<String> eqCols = new LinkedHashSet<>();
        for (String pred : wherePredicates(sql)) {
            Matcher m = COMPARE.matcher(pred);
            if (!m.find()) {
                continue;
            }
            String lhs = m.group(1).trim();
            String op = m.group(2).trim().toLowerCase();
            String rhs = m.group(3).trim();

            if (FUNC_ON_COL.matcher(lhs).find() || ARITH_ON_COL.matcher(lhs).find()) {
                causes.add(new RootCause("컬럼에 함수/표현식",
                        "WHERE에서 컬럼이 함수/연산 안에 있음: " + lhs,
                        "옵티마이저가 표현식 결과를 미리 알 수 없어 인덱스가 무력화된다 — 함수를 상수 쪽으로 옮기거나 함수기반 인덱스를 검토."));
                continue;
            }
            if (op.equals("like") && stripQuote(rhs).startsWith("%")) {
                causes.add(new RootCause("앞 와일드카드 LIKE",
                        "LIKE 조건이 '%'로 시작: " + pred,
                        "B+Tree는 선두부터 시작점을 잡는데 앞이 와일드카드면 시작점을 못 잡아 인덱스를 못 탄다."));
                continue;
            }
            String col = stripQualifier(lhs);
            if (!IDENT.matcher(col).matches()) {
                continue;
            }
            if (NUMERIC.matcher(rhs).matches()) {
                String colType = columnType(schema, col);
                if (isStringType(colType)) {
                    causes.add(new RootCause("암시적 형변환",
                            "문자열 컬럼(" + col + " " + colType + ")을 숫자 리터럴(" + rhs + ")과 비교",
                            "문자열=숫자 비교 시 '1'·' 1'·'1a'가 모두 1로 변환돼 컬럼 전체를 변환해야 하므로 인덱스 순서를 못 쓴다 — "
                                    + "비교값을 문자열('" + rhs + "')로 주거나 컬럼 타입을 맞춰라."));
                    continue;
                }
            }
            if (op.equals("=")) {
                eqCols.add(col);
            }
        }

        for (String col : eqCols) {
            if (!isLeadingColumn(schema, col) && isNonLeadingMember(schema, col)) {
                causes.add(new RootCause("복합 인덱스 선두 누락",
                        "WHERE의 " + col + "이 복합 인덱스의 선두 컬럼이 아님(선두 조건 없음)",
                        "복합 인덱스는 선두 컬럼부터 시작점을 잡는다 — 선두 없이 뒷 컬럼만 조건이면 인덱스를 못 탄다."));
            }
        }

        // 구조적 원인이 없는데 추정·실제 괴리만 크면 통계 노후를 후보로(D2와 짝)
        if (gap != null && gap.ratio() >= GAP_THRESHOLD && causes.isEmpty()) {
            causes.add(new RootCause("통계 노후(후보)",
                    "추정 " + gap.estimatedRows() + " vs 실제 " + gap.actualRows()
                            + " (약 " + Math.round(gap.ratio()) + "배 괴리)인데 형변환·함수·선두 누락 신호는 없음",
                    "옵티마이저가 옛 행수·분포로 추정해 틀렸을 가능성 — 통계 재수집을 검토하라(D2 stale-statistics Advisor와 함께 확인)."));
        }
        return dedupByCause(causes);
    }

    /** 같은 근본원인이 여러 신호로 중복될 수 있다(예: 플랜의 CONVERT_IMPLICIT + 스키마 타입 대조) — 첫 신호만 남긴다. */
    private static List<RootCause> dedupByCause(List<RootCause> causes) {
        List<RootCause> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RootCause c : causes) {
            if (seen.add(c.cause())) {
                out.add(c);
            }
        }
        return out;
    }

    private List<RootCause> mongoRootCauses(String plan, CardinalityGap gap) {
        List<RootCause> causes = new ArrayList<>();
        if (plan != null && plan.contains("COLLSCAN")) {
            causes.add(new RootCause("인덱스 부재(COLLSCAN)",
                    "winningPlan에 COLLSCAN — 필터를 받는 인덱스가 없음",
                    "필터 컬럼에 인덱스가 없어 컬렉션 전체를 훑는다 — 필터 필드에 인덱스를 검토."));
        }
        if (gap != null) {
            causes.add(new RootCause("스캔 낭비(낮은 선택도)",
                    "docsExamined(" + gap.actualRows() + ") ÷ nReturned(" + gap.estimatedRows()
                            + ") = 약 " + Math.round(gap.ratio()) + "배",
                    "반환 문서 1건당 훑은 문서가 많다 — 인덱스로 스캔 대상을 좁혀야 한다."));
        }
        return causes;
    }

    // ---------- SQL/스키마 유틸 ----------

    private List<String> wherePredicates(String sql) {
        if (sql == null) {
            return List.of();
        }
        Matcher wm = WHERE.matcher(sql);
        if (!wm.find()) {
            return List.of();
        }
        return Arrays.stream(wm.group(1).split("(?i)\\band\\b|\\bor\\b"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String columnType(SchemaSnapshot schema, String col) {
        if (schema == null) {
            return null;
        }
        for (TableSchema t : schema.tables()) {
            for (ColumnSchema c : t.columns()) {
                if (c.name().equalsIgnoreCase(col)) {
                    return c.type();
                }
            }
        }
        return null;
    }

    private boolean isLeadingColumn(SchemaSnapshot schema, String col) {
        if (schema == null) {
            return false;
        }
        for (TableSchema t : schema.tables()) {
            for (IndexSchema idx : t.indexes()) {
                if (!idx.columns().isEmpty() && idx.columns().get(0).equalsIgnoreCase(col)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isNonLeadingMember(SchemaSnapshot schema, String col) {
        if (schema == null) {
            return false;
        }
        for (TableSchema t : schema.tables()) {
            for (IndexSchema idx : t.indexes()) {
                List<String> cols = idx.columns();
                for (int i = 1; i < cols.size(); i++) {
                    if (cols.get(i).equalsIgnoreCase(col)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isStringType(String type) {
        if (type == null) {
            return false;
        }
        String s = type.toLowerCase();
        return s.contains("char") || s.contains("text") || s.contains("clob") || s.contains("string");
    }

    private static String stripQualifier(String lhs) {
        int dot = lhs.lastIndexOf('.');
        return (dot >= 0 ? lhs.substring(dot + 1) : lhs).trim();
    }

    private static String stripQuote(String s) {
        return s.replaceAll("^[NnEe]?['\"]", "");
    }

    // ---------- 숫자/포맷 유틸 ----------

    private static double ratio(double a, double b) {
        double hi = Math.max(a, b);
        double lo = Math.min(a, b);
        if (lo <= 0) {
            lo = 1; // 0 방어 — 괴리는 드러내되 무한대를 피한다
        }
        return hi / lo;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double parseD(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Oracle A-Rows/E-Rows는 K(천)·M(백만)·G(십억) 접미사로 축약된다 — 원수로 되돌린다. */
    private static Long parseOracleNum(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'K', 'k' -> {
                mult = 1_000;
                s = s.substring(0, s.length() - 1);
            }
            case 'M', 'm' -> {
                mult = 1_000_000;
                s = s.substring(0, s.length() - 1);
            }
            case 'G', 'g' -> {
                mult = 1_000_000_000;
                s = s.substring(0, s.length() - 1);
            }
            default -> { }
        }
        try {
            return Math.round(Double.parseDouble(s.trim()) * mult);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String mysqlLabel(String line) {
        String s = line.trim();
        if (s.startsWith("->")) {
            s = s.substring(2).trim();
        }
        int costAt = s.indexOf("(cost=");
        return (costAt > 0 ? s.substring(0, costAt) : s).trim();
    }
}
