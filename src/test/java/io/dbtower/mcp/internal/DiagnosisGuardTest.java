package io.dbtower.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.mcp.internal.DiagnosisGuard.CallerScope;
import io.dbtower.mcp.internal.DiagnosisGuard.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 진단 루프 도구 호출 정책 — "검사한 값이 곧 실행하는 값"과 호출자 범위를 경계값으로 검증한다.
 * 핸들러는 asLong()으로 관대하게 변환하므로("2"→2, true→1) 검사도 그 입력들을 직접 다룬다.
 */
class DiagnosisGuardTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CallerScope teamOne = new CallerScope(false, Set.of(1L, 3L));

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void 대상과_다른_instanceId는_거부한다() throws Exception {
        Verdict v = DiagnosisGuard.check(1, CallerScope.GLOBAL, "health", json("{\"instanceId\":2}"));
        assertTrue(v.rejected(), "전역 주체라도 진단 대상 밖 인스턴스는 부를 수 없다");
        assertNull(v.arguments());
    }

    @Test
    void 문자열로_준_다른_id도_거부한다() throws Exception {
        // 핸들러의 asLong()은 "2"를 2로 실행한다 — 숫자 노드만 검사하면 문자열로 우회된다
        assertTrue(DiagnosisGuard.check(1, teamOne, "health", json("{\"instanceId\":\"2\"}")).rejected());
    }

    @Test
    void 해석할_수_없는_instanceId는_거부한다() throws Exception {
        // asLong()은 true를 1로 바꾼다 — 대상이 1이어도 의미 없는 값은 받지 않는다
        assertTrue(DiagnosisGuard.check(1, teamOne, "health", json("{\"instanceId\":true}")).rejected());
        assertTrue(DiagnosisGuard.check(1, teamOne, "health", json("{\"instanceId\":1.5}")).rejected());
        assertTrue(DiagnosisGuard.check(1, teamOne, "health", json("{\"instanceId\":\"1 OR 2\"}")).rejected());
    }

    @Test
    void instanceId를_빠뜨리거나_문자열로_주면_대상_숫자로_고정한다() throws Exception {
        Verdict missing = DiagnosisGuard.check(1, teamOne, "sessions", json("{\"limit\":10}"));
        assertFalse(missing.rejected());
        assertEquals(1L, missing.arguments().get("instanceId").asLong());
        assertTrue(missing.arguments().get("instanceId").isIntegralNumber());
        assertEquals(10, missing.arguments().get("limit").asInt(), "다른 인자는 보존한다");

        Verdict textual = DiagnosisGuard.check(1, teamOne, "health", json("{\"instanceId\":\"1\"}"));
        assertTrue(textual.arguments().get("instanceId").isIntegralNumber(), "실행 인자는 정규화한 숫자");
    }

    @Test
    void 원본_인자는_바꾸지_않는다() throws Exception {
        JsonNode original = json("{\"limit\":5}");
        DiagnosisGuard.check(1, teamOne, "sessions", original);
        assertFalse(original.has("instanceId"), "트레이스에 남는 AI 원 요청이 변조되면 투명성이 깨진다");
    }

    @Test
    void schema_diff는_한쪽이_대상이고_양쪽이_범위_안이어야_한다() throws Exception {
        assertTrue(DiagnosisGuard.check(1, teamOne, "schema_diff", json("{\"left\":3,\"right\":4}")).rejected(),
                "대상과 무관한 두 인스턴스 비교");
        assertTrue(DiagnosisGuard.check(1, teamOne, "schema_diff", json("{\"left\":1,\"right\":2}")).rejected(),
                "범위 밖(2) 인스턴스와 비교");
        assertTrue(DiagnosisGuard.check(1, teamOne, "schema_diff", json("{\"left\":1}")).rejected());

        Verdict ok = DiagnosisGuard.check(1, teamOne, "schema_diff", json("{\"left\":\"3\",\"right\":1}"));
        assertFalse(ok.rejected());
        assertTrue(ok.arguments().get("left").isIntegralNumber());
        assertFalse(ok.arguments().has("instanceId"), "schema_diff에는 instanceId를 끼워 넣지 않는다");
    }

    @Test
    void list_instances는_팀_범위_주체에게_보이는_것만_남긴다() {
        String all = "[{\"id\":1,\"name\":\"orders\"},{\"id\":2,\"name\":\"billing-other-team\"},{\"id\":3,\"name\":\"shared\"}]";
        String filtered = DiagnosisGuard.filterObservation("list_instances", all, teamOne, mapper);
        assertTrue(filtered.contains("orders") && filtered.contains("shared"));
        assertFalse(filtered.contains("billing-other-team"));

        assertEquals(all, DiagnosisGuard.filterObservation("list_instances", all, CallerScope.GLOBAL, mapper));
        assertEquals("x", DiagnosisGuard.filterObservation("health", "x", teamOne, mapper), "다른 도구는 손대지 않는다");
    }

    @Test
    void list_instances_결과를_해석할_수_없으면_숨긴다() {
        assertEquals(DiagnosisGuard.HIDDEN_OBSERVATION,
                DiagnosisGuard.filterObservation("list_instances", "도구 실행 실패: 500", teamOne, mapper));
        assertEquals(DiagnosisGuard.HIDDEN_OBSERVATION,
                DiagnosisGuard.filterObservation("list_instances", "{\"id\":2}", teamOne, mapper));
    }
}
