package io.dbtower.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 회귀 감지 결과에 대한 AI 1차 분석 (확장3).
 *
 * 판단을 통째로 LLM에 맡기지 않는다 — docs/ai-analysis-rules.md의 기종별 판단 기준을
 * 시스템 프롬프트로 넣어, 같은 입력에 일관된 판정이 나오게 한다.
 *
 * 백엔드는 환경에 맞게 자동 선택된다:
 * - api: ANTHROPIC_API_KEY가 있으면 Anthropic Java SDK (운영 구성)
 * - cli: 키가 없어도 claude CLI가 설치돼 있으면 headless 모드(claude -p)로 호출 —
 *        로컬 개발에서는 별도 API 키 없이 Claude 구독으로 동작한다
 * - off: 둘 다 없으면 조용히 비활성화 (분석 실패가 알림 자체를 막지 않는다)
 */
@Component
public class AiAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AiAnalyzer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private enum Mode { API, CLI, OFF }

    private static final String SYSTEM_PROMPT = """
            당신은 DB 운영 플랫폼의 1차 분석기다. 아래 회귀 감지 결과를 보고
            가장 유력한 원인과 확인할 것을 한국어 3~5문장으로 제시한다.
            반드시 아래 판단 기준 문서에 근거해서만 판정하고, 근거가 없으면 모른다고 말한다.
            수치를 지어내지 않는다.

            [판단 기준 문서]
            """;

    private final String model;
    private final Path rulesPath;
    private final long maxTokens;
    private final Mode mode;
    private volatile AnthropicClient client;

    public AiAnalyzer(@Value("${dbtower.ai.model:claude-opus-4-8}") String model,
                      @Value("${dbtower.ai.rules-path:docs/ai-analysis-rules.md}") String rulesPath,
                      // adaptive thinking이 켜져 있어 이 예산은 "추론 + 응답"의 합산 상한이다.
                      // 너무 낮으면 추론이 예산을 먹고 응답이 중간에서 잘린다(D3 루프가 그걸 형식 오류로 오인).
                      @Value("${dbtower.ai.max-tokens:8192}") long maxTokens) {
        this.model = model;
        this.rulesPath = Path.of(rulesPath);
        this.maxTokens = maxTokens;
        this.mode = detectMode();
        switch (mode) {
            case API -> log.info("AI 1차 분석 활성화 — Anthropic API (model={})", model);
            case CLI -> log.info("AI 1차 분석 활성화 — claude CLI headless (구독 기반, 로컬 개발용)");
            case OFF -> log.info("ANTHROPIC_API_KEY도 claude CLI도 없음 — AI 1차 분석 비활성화 (규칙 기반 알림만 발송)");
        }
    }

    private static Mode detectMode() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && !key.isBlank()) {
            return Mode.API;
        }
        return claudeCliAvailable() ? Mode.CLI : Mode.OFF;
    }

    private static boolean claudeCliAvailable() {
        try {
            Process p = new ProcessBuilder("claude", "--version")
                    .redirectErrorStream(true).start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** AI 백엔드가 하나라도 있는지 — D3 자연어 진단이 켜지는지 판단에 쓴다. */
    public boolean isEnabled() {
        return mode != Mode.OFF;
    }

    /** 활성 백엔드 이름(api/cli/off) — 응답 투명성용. */
    public String backend() {
        return switch (mode) {
            case API -> "api";
            case CLI -> "cli";
            case OFF -> "off";
        };
    }

    public Optional<String> analyze(String findingContext) {
        return complete(SYSTEM_PROMPT + loadRules(), findingContext);
    }

    /**
     * 임의의 시스템 프롬프트 + 사용자 메시지로 1회 완성 호출 — 백엔드(API/CLI)를 추상화한다.
     * D3 도구 사용 루프가 한 스텝(다음에 어떤 도구를 부를지 결정)마다 이걸 부른다.
     * 실패·시간초과는 예외를 던지지 않고 빈 값으로 정직하게 내려간다.
     */
    public Optional<String> complete(String systemPrompt, String userMessage) {
        if (mode == Mode.OFF) {
            return Optional.empty();
        }
        try {
            String text = switch (mode) {
                case API -> callApi(systemPrompt, userMessage);
                case CLI -> callCli(systemPrompt, userMessage);
                case OFF -> "";
            };
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
        } catch (Exception e) {
            // 분석 실패는 알림·진단을 막지 않는다
            log.warn("AI 호출 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String loadRules() {
        try {
            return Files.exists(rulesPath) ? Files.readString(rulesPath) : "";
        } catch (Exception e) {
            log.warn("판단 기준 문서 로드 실패: {}", e.getMessage());
            return "";
        }
    }

    private String callApi(String system, String user) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(system)
                .addUserMessage(user)
                .build();
        Message message = client().messages().create(params);
        logUsage(message);
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            // 절단본을 그대로 올리면 안 된다 — 잘린 JSON을 받은 D3 루프가 "형식 밖 텍스트"로
            // 오귀속해 남은 스텝을 돌지 않고 진단을 끝낸다. 예산 부족은 예산 부족이라고 말한다.
            throw new IllegalStateException(
                    "응답이 max_tokens(" + maxTokens + ")에 걸려 잘렸습니다 — 예산을 올리거나 effort를 낮추세요");
        }
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .reduce("", String::concat);
    }

    /**
     * 토큰 사용량 관측점 — 캐시가 실제로 걸리는지, 응답이 예산에 걸리는지는 이 로그로만 보인다.
     * cacheRead가 반복 호출에서 계속 0이면 프리픽스를 깨는 값이 프롬프트 앞쪽에 남아 있다는 뜻이다.
     */
    private static void logUsage(Message message) {
        if (!log.isDebugEnabled()) {
            return;
        }
        var usage = message.usage();
        log.debug("AI usage — stop={} input={} cacheWrite={} cacheRead={} output={}",
                message.stopReason().map(StopReason::asString).orElse("-"),
                usage.inputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                usage.outputTokens());
    }

    /**
     * claude CLI headless 호출 — 프롬프트는 argv가 아니라 stdin으로 전달한다
     * (SQL·실행계획에 어떤 문자가 와도 인자 파싱과 무관하게 안전).
     */
    private String callCli(String system, String user) throws Exception {
        // --setting-sources "": 사용자/프로젝트 설정(출력 스타일 등)을 배제해
        // 어떤 로컬 환경에서도 같은 형식의 결과가 나오게 한다.
        // --output-format json: 본문만이 아니라 usage·stop_reason이 실린 봉투를 받는다 —
        // 구독(CLI) 경로에도 API 경로와 같은 관측점을 두어야 캐시·절단을 짝비교로 잴 수 있다.
        Process p = new ProcessBuilder("claude", "-p", "--output-format", "json",
                "--setting-sources", "", "--append-system-prompt", system)
                .redirectErrorStream(false).start();
        try (var stdin = p.getOutputStream()) {
            stdin.write(user.getBytes(StandardCharsets.UTF_8));
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("claude CLI 응답 시간 초과");
        }
        if (p.exitValue() != 0) {
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("claude CLI 종료 코드 " + p.exitValue() + ": " + err.trim());
        }
        return extractCliResult(out);
    }

    /**
     * claude CLI의 JSON 봉투에서 본문을 꺼내고, 그 김에 usage·절단을 관측한다.
     *
     * <p>봉투가 아니면(구버전 CLI·형식 변경) 평문으로 간주해 그대로 올린다 — 형식이 바뀌었다고
     * 진단 자체가 죽으면 안 된다. Spring 없이도 테스트 가능하도록 static.
     */
    static String extractCliResult(String raw) {
        JsonNode env;
        try {
            env = MAPPER.readTree(raw);
        } catch (Exception e) {
            log.debug("claude CLI JSON 봉투 파싱 실패 — 평문으로 처리: {}", e.getMessage());
            return raw;
        }
        if (env == null || !env.isObject() || !env.hasNonNull("result")) {
            return raw;
        }
        logCliUsage(env);

        // API 경로와 같은 규칙 — 절단본을 그대로 올리면 호출부가 "형식 밖 텍스트"로 오귀속한다
        if ("max_tokens".equals(env.path("stop_reason").asText(""))) {
            throw new IllegalStateException("claude CLI 응답이 max_tokens에 걸려 잘렸습니다");
        }
        if (env.path("is_error").asBoolean(false)) {
            throw new IllegalStateException(
                    "claude CLI 오류: " + env.path("subtype").asText("unknown"));
        }
        return env.path("result").asText("");
    }

    /** API 경로의 logUsage와 같은 필드 순서로 찍는다 — 두 경로를 같은 눈금으로 비교하기 위함. */
    private static void logCliUsage(JsonNode env) {
        if (!log.isDebugEnabled()) {
            return;
        }
        JsonNode u = env.path("usage");
        log.debug("AI usage(cli) — stop={} input={} cacheWrite={} cacheRead={} output={} costUsd={}",
                env.path("stop_reason").asText("-"),
                u.path("input_tokens").asLong(),
                u.path("cache_creation_input_tokens").asLong(),
                u.path("cache_read_input_tokens").asLong(),
                u.path("output_tokens").asLong(),
                env.path("total_cost_usd").asDouble());
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.fromEnv();
                }
            }
        }
        return client;
    }
}
