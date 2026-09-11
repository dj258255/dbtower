package io.dbtower.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * claude CLI stream-json 한 줄 해석(VERIFICATION 141절). 줄 모양은 claude 2.1.268에서 실제로 받은 출력을 줄여 옮겼다 —
 * 형식이 바뀌면 조각이 안 흐를 뿐 완성본은 결과 줄로 여전히 올라가야 한다.
 */
class AiAnalyzerStreamLineTest {

    @Test
    void 최상위_대화의_text_delta만_조각으로_꺼낸다() {
        String delta = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"하\"}},\"session_id\":\"s\",\"parent_tool_use_id\":null}";
        assertThat(AiAnalyzer.readCliStreamLine(delta).text()).isEqualTo("하");
    }

    @Test
    void 도구_하위_에이전트의_조각과_생각_조각은_흘리지_않는다() {
        String sub = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}},\"parent_tool_use_id\":\"toolu_1\"}";
        String thinking = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"...\"}},\"parent_tool_use_id\":null}";
        assertThat(AiAnalyzer.readCliStreamLine(sub)).isEqualTo(AiAnalyzer.CliStreamLine.NONE);
        assertThat(AiAnalyzer.readCliStreamLine(thinking)).isEqualTo(AiAnalyzer.CliStreamLine.NONE);
    }

    @Test
    void 결과_줄은_json_출력과_같은_봉투라_기존_추출기로_본문이_나온다() {
        String result = "{\"duration_api_ms\":1387,\"stop_reason\":\"end_turn\",\"is_error\":false,\"subtype\":\"success\","
                + "\"type\":\"result\",\"result\":\"하나\\n둘\",\"usage\":{\"input_tokens\":2,\"output_tokens\":17}}";
        AiAnalyzer.CliStreamLine line = AiAnalyzer.readCliStreamLine(result);
        assertThat(line.resultEnvelope()).isEqualTo(result);
        assertThat(AiAnalyzer.extractCliResult(line.resultEnvelope())).isEqualTo("하나\n둘");
    }

    @Test
    void JSON이_아닌_줄과_다른_이벤트는_무시한다() {
        assertThat(AiAnalyzer.readCliStreamLine("not json")).isEqualTo(AiAnalyzer.CliStreamLine.NONE);
        assertThat(AiAnalyzer.readCliStreamLine("{\"type\":\"system\",\"subtype\":\"init\"}"))
                .isEqualTo(AiAnalyzer.CliStreamLine.NONE);
    }
}
