package io.dbtower.insight.internal.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.dbtower.insight.internal.MetabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/**
 * lakehouse 장기 마트 서빙 프록시 (15단계) — MCP 도구의 REST 뒷면.
 *
 * <p>3계층 분업(15단계 계약): 라이브 7일 = 기존 도구들, 장기 = 여기(Metabase 경유 DuckLake
 * 마트), 관제 대상 DB 직결 = 금지. 질의는 SELECT 전용 가드로 봉인한다 — 마트는 read-only
 * 서빙 계층이고, 쓰기는 이 경로에 존재하지 않는다(원천 무오염 원칙의 분석계판).
 *
 * <p>기능 게이트: Metabase 미설정이면 404 — 있는 척하지 않는다(Discord 슬래시 게이트 관례).
 */
@RestController
@RequestMapping("/api/lakehouse")
public class LakehouseController {

    private final MetabaseClient metabase;

    public LakehouseController(MetabaseClient metabase) {
        this.metabase = metabase;
    }

    /**
     * SELECT 전용 가드(순수 로직 — 테스트 대상). 주석 제거 후 SELECT/WITH로 시작해야 하고,
     * 문장 분리(;)와 쓰기/DDL 키워드를 거부한다. 완전한 SQL 파서가 아니라 방어선이다 —
     * 최종 방어는 Metabase 커넥션 자체가 DuckLake를 read-only로 무는 구조다.
     */
    static String rejectIfNotReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            return "sql이 비었다";
        }
        String s = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\n]*", " ").trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            return "SELECT/WITH로 시작하는 읽기 질의만 허용한다";
        }
        if (lower.contains(";")) {
            return "문장 분리(;)는 허용하지 않는다";
        }
        for (String kw : new String[]{"insert ", "update ", "delete ", "drop ", "alter ",
                "create ", "truncate ", "grant ", "attach ", "copy ", "install ", "load "}) {
            if (lower.contains(kw)) {
                return "쓰기/DDL 키워드(" + kw.trim() + ")는 허용하지 않는다";
            }
        }
        return null;
    }

    public record QueryRequest(String sql, Integer rowLimit) {
    }

    public record CardRequest(String title, String sql, String display) {
    }

    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody QueryRequest req) throws Exception {
        if (!metabase.enabled()) {
            return ResponseEntity.notFound().build(); // 기능 게이트 — 미설정이면 없는 기능이다
        }
        String reject = rejectIfNotReadOnly(req.sql());
        if (reject != null) {
            return ResponseEntity.badRequest().body(Map.of("error", reject));
        }
        int limit = req.rowLimit() == null ? 200 : Math.min(req.rowLimit(), 2000);
        JsonNode result = metabase.nativeQuery(req.sql(), limit);
        // Boot 4 기본 직렬화(Jackson 3)가 Jackson 2 JsonNode를 POJO로 취급한다 —
        // 노드의 JSON 문자열을 그대로 내보낸다(계약은 JSON 본문).
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(result.toString());
    }

    @PostMapping("/cards")
    public ResponseEntity<?> createCard(@RequestBody CardRequest req) throws Exception {
        if (!metabase.enabled()) {
            return ResponseEntity.notFound().build();
        }
        String reject = rejectIfNotReadOnly(req.sql());
        if (reject != null) {
            return ResponseEntity.badRequest().body(Map.of("error", reject));
        }
        if (req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title이 비었다"));
        }
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(metabase.createCard(req.title(), req.sql(), req.display()).toString());
    }
}
