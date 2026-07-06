package io.dbtower.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 실행계획 원문(기종별 JSON/XML/텍스트) → 비교 가능한 <b>형태(shape)</b> 문자열.
 *
 * 플랜 변경(plan flip) 감지의 판정 기준. 핵심 원칙: <b>구조는 남기고 수치는 버린다</b>.
 * 비용·추정 행수는 통계가 조금만 변해도 흔들리므로 shape에 남으면 매번 "가짜 변경"이 되고,
 * 노드 종류·인덱스·대상 테이블은 옵티마이저의 실제 선택이라 남겨야 한다.
 *
 * 기종마다 계획 표현이 전혀 다르므로(PG는 JSON 트리, MySQL은 EXPLAIN FORMAT=JSON, MSSQL은
 * showplan XML, Oracle은 plan_hash_value, Mongo는 winningPlan) 기종별 메서드로 나눈다.
 * 각 Operator는 자기 계획을 얻어 여기 맞는 메서드를 호출한다 — 포맷 지식을 한 곳에 모은다.
 */
public final class PlanShapes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanShapes() {
    }

    // ---------- PostgreSQL: EXPLAIN (FORMAT JSON) ----------

    /** PG 계획 JSON → "Node Type(대상)>[자식,...]" 트리. Node Type·Index Name·Relation Name만 남긴다. */
    public static String fromPgJson(String planJson) {
        try {
            JsonNode root = MAPPER.readTree(planJson);
            JsonNode plan = root.isArray() ? root.get(0).path("Plan") : root.path("Plan");
            StringBuilder sb = new StringBuilder();
            walkPg(plan, sb);
            return sb.toString();
        } catch (Exception e) {
            return fromText(planJson); // 파싱 실패 시 텍스트 폴백 — 거칠어질 뿐 감지는 계속
        }
    }

    private static void walkPg(JsonNode node, StringBuilder sb) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        sb.append(node.path("Node Type").asText("?"));
        String index = node.path("Index Name").asText("");
        String rel = node.path("Relation Name").asText("");
        if (!index.isEmpty()) {
            sb.append("(").append(index).append(")");
        } else if (!rel.isEmpty()) {
            sb.append("(").append(rel).append(")");
        }
        JsonNode children = node.path("Plans");
        if (children.isArray() && !children.isEmpty()) {
            sb.append(">[");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                walkPg(children.get(i), sb);
            }
            sb.append("]");
        }
    }

    // ---------- MySQL: EXPLAIN FORMAT=JSON ----------

    /**
     * MySQL 계획 JSON → 테이블 접근 토큰의 순서열. 각 table 노드에서 access_type(테이블[:인덱스])만
     * 뽑는다 — 예: "ALL(products)" (풀스캔) vs "ref(products:idx_code)" (인덱스). cost_info·rows는 버린다.
     */
    public static String fromMysqlJson(String planJson) {
        try {
            JsonNode root = MAPPER.readTree(planJson);
            StringBuilder sb = new StringBuilder();
            collectMysqlTables(root, sb);
            return sb.length() == 0 ? fromText(planJson) : sb.toString();
        } catch (Exception e) {
            return fromText(planJson);
        }
    }

    private static void collectMysqlTables(JsonNode node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode table = node.get("table");
            if (table != null && table.hasNonNull("table_name")) {
                if (sb.length() > 0) {
                    sb.append(">");
                }
                String access = table.path("access_type").asText("?");
                String name = table.path("table_name").asText("?");
                sb.append(access).append("(").append(name);
                String key = table.path("key").asText("");
                if (!key.isEmpty()) {
                    sb.append(":").append(key);
                }
                sb.append(")");
                // table 안의 하위 구조(attached_subqueries·materialized_from_subquery)도 이어서 탐색
                node.fields().forEachRemaining(e -> {
                    if (!e.getKey().equals("table")) {
                        collectMysqlTables(e.getValue(), sb);
                    }
                });
                collectMysqlTables(table, sb);
                return;
            }
            node.forEach(child -> collectMysqlTables(child, sb));
        } else if (node.isArray()) {
            node.forEach(child -> collectMysqlTables(child, sb));
        }
    }

    // ---------- MongoDB: explain queryPlanner.winningPlan ----------

    /**
     * Mongo winningPlan → "stage(인덱스)>[자식,...]". stage와 indexName만 남긴다 —
     * 예: "FETCH>[IXSCAN(idx_k)]" vs "COLLSCAN". filter·direction 등 수치성은 버린다.
     */
    public static String fromMongoPlan(String planJson) {
        try {
            JsonNode root = MAPPER.readTree(planJson);
            JsonNode winning = findFirst(root, "winningPlan");
            JsonNode start = winning != null ? winning : root;
            StringBuilder sb = new StringBuilder();
            walkMongo(start, sb);
            return sb.length() == 0 ? fromText(planJson) : sb.toString();
        } catch (Exception e) {
            return fromText(planJson);
        }
    }

    private static void walkMongo(JsonNode node, StringBuilder sb) {
        if (node == null || !node.isObject()) {
            return;
        }
        String stage = node.path("stage").asText("");
        if (stage.isEmpty()) {
            return;
        }
        sb.append(stage);
        String index = node.path("indexName").asText("");
        if (!index.isEmpty()) {
            sb.append("(").append(index).append(")");
        }
        JsonNode input = node.get("inputStage");
        JsonNode inputs = node.get("inputStages");
        if (input != null && input.isObject()) {
            sb.append(">[");
            walkMongo(input, sb);
            sb.append("]");
        } else if (inputs != null && inputs.isArray() && !inputs.isEmpty()) {
            sb.append(">[");
            for (int i = 0; i < inputs.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                walkMongo(inputs.get(i), sb);
            }
            sb.append("]");
        }
    }

    private static JsonNode findFirst(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        if (node.has(field)) {
            return node.get(field);
        }
        for (JsonNode child : node) {
            JsonNode found = findFirst(child, field);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ---------- SQL Server: showplan XML ----------

    /**
     * MSSQL showplan XML → "PhysicalOp(인덱스)>[...]". RelOp의 PhysicalOp와 접근 객체의 Index만 남긴다 —
     * 예: "Index Seek(idx_x)" vs "Clustered Index Scan(PK_t)". 비용·행수 추정은 버린다.
     */
    public static String fromMssqlXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true); // showplan XML은 기본 네임스페이스가 있어 localName 비교에 필요
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element top = firstRelOp(doc.getDocumentElement());
            if (top == null) {
                return fromText(xml);
            }
            StringBuilder sb = new StringBuilder();
            walkMssql(top, sb);
            return sb.length() == 0 ? fromText(xml) : sb.toString();
        } catch (Exception e) {
            return fromText(xml);
        }
    }

    private static Element firstRelOp(Node node) {
        if (node instanceof Element el && el.getLocalName() != null && el.getLocalName().equals("RelOp")) {
            return el;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Element found = firstRelOp(children.item(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void walkMssql(Element relOp, StringBuilder sb) {
        String op = relOp.getAttribute("PhysicalOp");
        sb.append(op.isEmpty() ? "?" : op);
        String index = firstIndexName(relOp);
        if (!index.isEmpty()) {
            sb.append("(").append(index).append(")");
        }
        java.util.List<Element> childOps = childRelOps(relOp);
        if (!childOps.isEmpty()) {
            sb.append(">[");
            for (int i = 0; i < childOps.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                walkMssql(childOps.get(i), sb);
            }
            sb.append("]");
        }
    }

    /** 이 RelOp의 직속 자식 RelOp들(손자 RelOp는 재귀에서 다룬다). */
    private static java.util.List<Element> childRelOps(Element relOp) {
        java.util.List<Element> result = new java.util.ArrayList<>();
        collectChildRelOps(relOp, relOp, result);
        return result;
    }

    private static void collectChildRelOps(Node node, Element root, java.util.List<Element> out) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c instanceof Element el && "RelOp".equals(el.getLocalName())) {
                out.add(el);
            } else if (c != null) {
                collectChildRelOps(c, root, out);
            }
        }
    }

    /** 이 RelOp의 접근 객체 Index 속성 — 자식 RelOp 경계는 넘지 않는다(그 인덱스는 자식 몫). */
    private static String firstIndexName(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (!(c instanceof Element el)) {
                continue;
            }
            if ("RelOp".equals(el.getLocalName())) {
                continue; // 자식 연산자 아래로는 내려가지 않는다
            }
            if ("Object".equals(el.getLocalName())) {
                String idx = el.getAttribute("Index");
                if (!idx.isEmpty()) {
                    return idx.replaceAll("[\\[\\]]", "");
                }
            }
            String nested = firstIndexName(el);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return "";
    }

    // ---------- 공통 ----------

    /** 텍스트 계획 폴백 — 숫자·공백을 지워 구조만 남긴다(Oracle 텍스트 계획·파싱 실패 시). */
    public static String fromText(String plan) {
        if (plan == null) {
            return "";
        }
        return plan.replaceAll("[0-9]+(\\.[0-9]+)?", "N").replaceAll("\\s+", " ").trim();
    }

    /** shape의 SHA-256 — 변경 판정은 이 해시 비교 한 번. */
    public static String hash(String shape) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(shape.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
