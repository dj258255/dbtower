package io.dbtower.analysis;

import org.junit.jupiter.api.Test;

import static io.dbtower.analysis.AiAnalyzer.extractCliResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * claude CLI(구독 경로) JSON 봉투 처리.
 *
 * 관심사는 두 가지다. (1) 본문을 정확히 꺼낼 것, (2) 절단·오류를 삼키지 말 것 —
 * 잘린 응답을 정상처럼 올리면 D3 루프가 "형식 밖 텍스트"로 오귀속해 진단을 조용히 끝낸다.
 * 반대로 봉투 형식이 바뀌었다고 진단이 죽어서도 안 되므로 평문 폴백은 유지한다.
 */
class AiAnalyzerTest {

    /** 실제 `claude -p --output-format json` 출력에서 관심 필드만 추린 형태. */
    private static String envelope(String stopReason, boolean isError, String result) {
        return """
                {"type":"result","subtype":"success","is_error":%s,"stop_reason":"%s",
                 "usage":{"input_tokens":2,"cache_creation_input_tokens":5866,
                          "cache_read_input_tokens":15607,"output_tokens":3},
                 "total_cost_usd":0.0665,"result":"%s"}
                """.formatted(isError, stopReason, result);
    }

    @Test
    void 정상_봉투에서_본문만_꺼낸다() {
        assertThat(extractCliResult(envelope("end_turn", false, "Seq Scan은 행수와 함께 봐야 한다.")))
                .isEqualTo("Seq Scan은 행수와 함께 봐야 한다.");
    }

    @Test
    void 절단된_응답은_예외로_올린다() {
        // 잘린 본문이 그대로 통과하면 호출부가 원인을 "형식 오류"로 잘못 보고한다
        assertThatThrownBy(() -> extractCliResult(envelope("max_tokens", false, "{\\\"action\\\":\\\"call_")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max_tokens");
    }

    @Test
    void CLI가_오류를_표시하면_예외로_올린다() {
        assertThatThrownBy(() -> extractCliResult(envelope("end_turn", true, "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claude CLI 오류");
    }

    @Test
    void 봉투가_아니면_평문으로_그대로_올린다() {
        // 구버전 CLI나 형식 변경에 대비한 폴백 — 형식이 바뀌었다고 진단이 죽으면 안 된다
        assertThat(extractCliResult("Seq Scan 하나로는 단정할 수 없다."))
                .isEqualTo("Seq Scan 하나로는 단정할 수 없다.");
        assertThat(extractCliResult("{\"type\":\"result\",\"subtype\":\"success\"}"))
                .isEqualTo("{\"type\":\"result\",\"subtype\":\"success\"}");
    }
}
