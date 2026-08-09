package io.dbtower.analysis;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static io.dbtower.analysis.AiAnalyzer.CallSite;
import static io.dbtower.analysis.AiAnalyzer.extractCliResult;
import static io.dbtower.analysis.AiAnalyzer.recordTokens;
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
    void API_요청은_시스템_프롬프트에_캐시_브레이크포인트를_건다() {
        // .system(String) 오버로드는 cache control을 실을 수 없다 — 블록 형태로 넘겨야 캐시가 걸린다.
        // 네트워크 없이 조립만 검증한다(잘못 조립하면 API 키가 있는 환경에서만 터진다).
        var params = AiAnalyzer.buildParams("claude-opus-4-8", 8192L, "medium", "판단 기준 문서", "질문");

        var blocks = params.system().orElseThrow().textBlockParams().orElseThrow();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("판단 기준 문서");
        assertThat(blocks.get(0).cacheControl()).isPresent();
        assertThat(params.outputConfig().orElseThrow().effort().orElseThrow().asString())
                .isEqualTo("medium");
    }

    @Test
    void 토큰_계수는_타입_백엔드_호출처로_나뉜다() {
        // 캐시 파손은 단일 호출로 판정할 수 없다(TTL 만료도 cache_read=0이다) — 비율로만 보인다.
        // 그래서 경고가 아니라 계수를 올리고, 비율은 대시보드에서 본다.
        MeterRegistry registry = new SimpleMeterRegistry();

        recordTokens(registry, "api", CallSite.DIAGNOSE, 2, 5369, 31040, 196);

        assertThat(tokens(registry, "api", "diagnose", "cache_write")).isEqualTo(5369);
        assertThat(tokens(registry, "api", "diagnose", "cache_read")).isEqualTo(31040);
        assertThat(tokens(registry, "api", "diagnose", "output")).isEqualTo(196);

        // 호출처가 섞이지 않는다 — D3 진단(1건이 여러 호출)과 회귀 1차 분석은 비용 구조가 달라
        // 합산해두면 어느 쪽을 손대야 하는지 알 수 없다.
        recordTokens(registry, "api", CallSite.REGRESSION, 100, 0, 0, 50);
        assertThat(tokens(registry, "api", "diagnose", "output")).isEqualTo(196);
        assertThat(tokens(registry, "api", "regression", "output")).isEqualTo(50);

        // 0도 시계열을 만든다 — 없는 시계열은 rate()가 비워서 비율 쿼리가 성립하지 않는다.
        assertThat(tokens(registry, "api", "regression", "cache_read")).isZero();
    }

    @Test
    void CLI_봉투의_usage도_같은_계수로_올라간다() {
        // API 경로에만 관측점이 있으면 두 경로를 같은 눈금으로 비교할 수 없다(118절 원칙).
        // 이 환경에는 API 키가 없으므로, 실제로 계수가 오르는 걸 확인할 수 있는 건 이 경로뿐이다.
        MeterRegistry registry = new SimpleMeterRegistry();

        extractCliResult(envelope("end_turn", false, "판정 결과"),
                env -> recordTokens(registry, "cli", CallSite.DIAGNOSE,
                        env.path("usage").path("input_tokens").asLong(),
                        env.path("usage").path("cache_creation_input_tokens").asLong(),
                        env.path("usage").path("cache_read_input_tokens").asLong(),
                        env.path("usage").path("output_tokens").asLong()));

        assertThat(tokens(registry, "cli", "diagnose", "cache_read")).isPositive();
    }

    @Test
    void 절단된_응답의_토큰도_계수에서_빠지지_않는다() {
        // 잘렸어도 토큰은 이미 청구됐다 — 예외 때문에 계수가 빠지면 비용 합계가 실제보다 작게 보인다.
        MeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> extractCliResult(envelope("max_tokens", false, "잘린 본문"),
                env -> recordTokens(registry, "cli", CallSite.DIAGNOSE, 0, 0, 0,
                        env.path("usage").path("output_tokens").asLong())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(tokens(registry, "cli", "diagnose", "output")).isPositive();
    }

    private static double tokens(MeterRegistry registry, String backend, String callSite, String type) {
        return registry.counter("dbtower.ai.tokens",
                "backend", backend, "call_site", callSite, "type", type).count();
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
