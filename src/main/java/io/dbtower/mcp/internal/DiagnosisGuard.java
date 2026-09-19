package io.dbtower.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * 진단 루프의 도구 호출 정책 — 대상 고정과 호출 주체의 인스턴스 범위를 코드로 강제한다.
 *
 * 왜 필요한가: 루프의 도구 실행은 서비스 토큰(ROLE_ADMIN)으로 자기 REST를 부르므로, REST 층의
 * 팀 스코프(RegistryService.findById)가 루프 안에서는 작동하지 않는다. 진입점은 대상 인스턴스 하나만
 * 확인했고, 나머지는 시스템 프롬프트의 "[대상] 값을 쓴다" 지시에 기대고 있었다. 그런데 도구 결과
 * (세션 쿼리 텍스트 등)는 대상 DB 사용자가 쓴 문자열이라 그 안의 지시문이 AI를 다른 인스턴스로
 * 돌릴 수 있다. 프롬프트는 경계가 될 수 없다.
 *
 * 검사한 값이 곧 실행하는 값이어야 한다: 핸들러는 asLong()으로 변환해 "2"나 true도 실행하므로,
 * 허용 판정 뒤 인자를 정규화한 id로 다시 써서 넘긴다.
 */
final class DiagnosisGuard {

    /** 인스턴스 인자가 없는 도구. 이 밖의 도구는 전부 instanceId를 대상 id로 고정한다(새 도구도 기본이 안전 쪽). */
    private static final Set<String> INSTANCE_FREE_TOOLS = Set.of("list_instances");

    static final String HIDDEN_OBSERVATION = "(호출자 범위 필터를 적용할 수 없어 결과를 숨겼다)";

    /** 호출 주체가 볼 수 있는 인스턴스 — 진단 시작 시점에 호출 스레드의 인증으로 한 번 확정한다. */
    record CallerScope(boolean global, Set<Long> visibleIds) {
        static final CallerScope GLOBAL = new CallerScope(true, Set.of());

        boolean canSee(long id) {
            return global || visibleIds.contains(id);
        }
    }

    /** 허용이면 실제로 실행할 인자(정규화한 사본), 거부면 사유. */
    record Verdict(JsonNode arguments, String rejection) {
        boolean rejected() {
            return rejection != null;
        }
    }

    private DiagnosisGuard() {
    }

    static Verdict check(long targetId, CallerScope scope, String tool, JsonNode arguments) {
        ObjectNode args = arguments != null && arguments.isObject()
                ? ((ObjectNode) arguments).deepCopy() : JsonNodeFactory.instance.objectNode();

        if (INSTANCE_FREE_TOOLS.contains(tool)) {
            return new Verdict(args, null);
        }
        if ("schema_diff".equals(tool)) {
            Long left = idOf(args, "left");
            Long right = idOf(args, "right");
            if (left == null || right == null) {
                return reject("schema_diff에는 해석 가능한 left·right 인스턴스 id가 필요하다");
            }
            if (left != targetId && right != targetId) {
                return reject("schema_diff의 한쪽은 진단 대상(instanceId=" + targetId + ")이어야 한다");
            }
            if (!scope.canSee(left) || !scope.canSee(right)) {
                return reject("호출자 범위 밖 인스턴스와는 비교할 수 없다");
            }
            args.put("left", left);
            args.put("right", right);
            return new Verdict(args, null);
        }
        if (args.has("instanceId")) {
            Long id = idOf(args, "instanceId");
            if (id == null || id != targetId) {
                return reject("instanceId는 진단 대상(" + targetId + ")만 쓸 수 있다. 요청값="
                        + args.get("instanceId"));
            }
        }
        args.put("instanceId", targetId);
        return new Verdict(args, null);
    }

    /**
     * list_instances는 서비스 토큰으로 전 인스턴스를 돌려받는다 — 팀 범위 호출자에게는 볼 수 있는 것만 남긴다.
     * 형식을 해석할 수 없으면(도구 실패 문구 등) 통과시키지 않고 숨긴다(fail-closed).
     */
    static String filterObservation(String tool, String observation, CallerScope scope, ObjectMapper mapper) {
        if (!"list_instances".equals(tool) || scope.global()) {
            return observation;
        }
        try {
            JsonNode all = mapper.readTree(observation);
            if (!all.isArray()) {
                return HIDDEN_OBSERVATION;
            }
            ArrayNode visible = mapper.createArrayNode();
            all.forEach(instance -> {
                JsonNode id = instance.path("id");
                if (id.canConvertToLong() && scope.canSee(id.asLong())) {
                    visible.add(instance);
                }
            });
            return visible.toString();
        } catch (Exception e) {
            return HIDDEN_OBSERVATION;
        }
    }

    /** 정수 또는 숫자만 담은 문자열이면 id, 그 밖(불리언·소수·문자·과대값)은 null — 핸들러의 관대한 변환을 믿지 않는다. */
    private static Long idOf(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (text.matches("\\d{1,18}")) {
                return Long.parseLong(text);
            }
        }
        return null;
    }

    private static Verdict reject(String reason) {
        return new Verdict(null, reason);
    }
}
