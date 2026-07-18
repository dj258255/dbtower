package io.dbtower.insight.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Metabase REST 클라이언트 (15단계 — 자연어 서빙의 배관).
 *
 * <p>왜 Metabase 경유인가: lakehouse 마트는 DuckLake(카탈로그=PG, 데이터=S3)에 살고, 그
 * 서빙 계층은 이미 Metabase다(lakehouse 7단계 계약 — "Metabase는 DuckLake만 read-only").
 * DBTower가 DuckDB JDBC+확장을 직접 얹는 대신 그 서빙 계층을 재사용하면 의존 0으로
 * 장기 마트 질의와 카드 생성이 모두 열린다. Metabot(Cloud 전용)이 셀프호스트에 없는
 * 갭을 이 조합(에이전트 → MCP → 여기 → Metabase)이 메운다.
 *
 * <p>인증: API 키(x-api-key, 권장) 또는 관리자 세션(email/password 로그인 후
 * X-Metabase-Session, 401이면 1회 재로그인). 기능 게이트 — url이 비면 enabled=false,
 * 소비자(컨트롤러)는 404로 응답한다(Discord 슬래시의 게이트 관례).
 */
@Component
public class MetabaseClient {

    private static final Logger log = LoggerFactory.getLogger(MetabaseClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String baseUrl;
    private final String apiKey;
    private final String email;
    private final String password;
    private final long configuredDbId;

    private volatile String session;      // email/password 경로의 세션 캐시
    private volatile long cachedDbId = -1; // DuckLake(duckdb) 데이터베이스 id 캐시

    public MetabaseClient(@Value("${dbtower.metabase.url:}") String baseUrl,
                          @Value("${dbtower.metabase.api-key:}") String apiKey,
                          @Value("${dbtower.metabase.email:}") String email,
                          @Value("${dbtower.metabase.password:}") String password,
                          @Value("${dbtower.metabase.database-id:0}") long configuredDbId) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.email = email;
        this.password = password;
        this.configuredDbId = configuredDbId;
    }

    public boolean enabled() {
        return !baseUrl.isBlank() && (!apiKey.isBlank() || (!email.isBlank() && !password.isBlank()));
    }

    /** DuckLake 마트에 네이티브 SQL 질의 — 행 제한을 서브쿼리로 강제한다. */
    public JsonNode nativeQuery(String sql, int rowLimit) throws Exception {
        long dbId = duckLakeDatabaseId();
        ObjectNode body = mapper.createObjectNode();
        body.put("database", dbId).put("type", "native");
        body.putObject("native").put("query",
                "SELECT * FROM (" + sql + ") mcp_q LIMIT " + Math.max(1, rowLimit));
        JsonNode res = call("POST", "/api/dataset", body.toString());
        // 응답을 에이전트 친화로 축약 — 컬럼명 + 행 배열 + 행수(전체 payload는 장황하다).
        ObjectNode out = mapper.createObjectNode();
        JsonNode data = res.path("data");
        var cols = out.putArray("columns");
        data.path("cols").forEach(c -> cols.add(c.path("name").asText()));
        out.set("rows", data.path("rows"));
        out.put("row_count", data.path("rows").size());
        if (!res.path("error").isMissingNode() && !res.path("error").isNull()) {
            out.put("error", res.path("error").asText());
        }
        return out;
    }

    /** "DBTower AI" 전용 컬렉션에 네이티브 질문 카드를 만들고 {card_id, url}을 돌려준다. */
    public JsonNode createCard(String title, String sql, String display) throws Exception {
        long dbId = duckLakeDatabaseId();
        long collectionId = ensureCollection("DBTower AI");
        ObjectNode body = mapper.createObjectNode();
        body.put("name", title)
                .put("display", display == null || display.isBlank() ? "table" : display)
                .put("collection_id", collectionId);
        ObjectNode dq = body.putObject("dataset_query");
        dq.put("database", dbId).put("type", "native");
        dq.putObject("native").put("query", sql);
        body.putObject("visualization_settings");
        JsonNode card = call("POST", "/api/card", body.toString());
        long id = card.path("id").asLong();
        ObjectNode out = mapper.createObjectNode();
        out.put("card_id", id);
        out.put("url", baseUrl + "/question/" + id);
        out.put("collection", "DBTower AI");
        return out;
    }

    /** duckdb 엔진 데이터베이스 id — 명시 설정이 있으면 그것, 없으면 /api/database에서 탐색·캐시. */
    private long duckLakeDatabaseId() throws Exception {
        if (configuredDbId > 0) {
            return configuredDbId;
        }
        if (cachedDbId > 0) {
            return cachedDbId;
        }
        JsonNode dbs = call("GET", "/api/database", null);
        for (JsonNode db : dbs.path("data")) {
            if ("duckdb".equalsIgnoreCase(db.path("engine").asText())) {
                cachedDbId = db.path("id").asLong();
                return cachedDbId;
            }
        }
        throw new IllegalStateException(
                "Metabase에 duckdb 엔진 데이터베이스가 없다 — DuckLake 커넥션을 먼저 만들 것"
                        + "(lakehouse RUNBOOK 5절) 또는 dbtower.metabase.database-id 명시");
    }

    private long ensureCollection(String name) throws Exception {
        JsonNode cols = call("GET", "/api/collection", null);
        for (JsonNode c : cols) {
            if (name.equals(c.path("name").asText()) && !c.path("archived").asBoolean(false)) {
                return c.path("id").asLong();
            }
        }
        ObjectNode body = mapper.createObjectNode().put("name", name);
        return call("POST", "/api/collection", body.toString()).path("id").asLong();
    }

    private JsonNode call(String method, String path, String body) throws Exception {
        JsonNode res = doCall(method, path, body, false);
        return res;
    }

    private JsonNode doCall(String method, String path, String body, boolean retried) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (!apiKey.isBlank()) {
            rb.header("x-api-key", apiKey);
        } else {
            rb.header("X-Metabase-Session", sessionToken(false));
        }
        rb.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401 && apiKey.isBlank() && !retried) {
            sessionToken(true); // 세션 만료 — 1회 재로그인 후 재시도
            return doCall(method, path, body, true);
        }
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("Metabase " + method + " " + path
                    + " 실패 HTTP " + res.statusCode() + ": "
                    + res.body().substring(0, Math.min(300, res.body().length())));
        }
        return mapper.readTree(res.body());
    }

    private synchronized String sessionToken(boolean force) throws Exception {
        if (session != null && !force) {
            return session;
        }
        ObjectNode body = mapper.createObjectNode().put("username", email).put("password", password);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("Metabase 로그인 실패 HTTP " + res.statusCode());
        }
        session = mapper.readTree(res.body()).path("id").asText();
        log.info("Metabase 세션 획득(관리자 로그인 경로)");
        return session;
    }
}
