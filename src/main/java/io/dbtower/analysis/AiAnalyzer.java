package io.dbtower.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
 *
 * 토큰 사용량은 두 백엔드 모두 {@code dbtower.ai.tokens} 카운터로 올라간다(METRIC 참고) —
 * 캐시가 걸리는지는 단일 호출로 판정할 수 없고 비율로만 보이기 때문이다.
 */
@Component
public class AiAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AiAnalyzer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 토큰 계수 이름 — 타입(input/cache_write/cache_read/output) × 백엔드 × 호출처로 태깅한다. */
    private static final String METRIC = "dbtower.ai.tokens";

    private enum Mode { API, CLI, OFF }

    /**
     * AI를 부르는 기능 축 — 카운터 태그로 쓴다.
     *
     * <p>문자열이 아니라 enum인 이유는 카디널리티를 구조적으로 묶기 위해서다. 호출처를 자유 문자열로
     * 받으면 누가 인스턴스 이름 같은 걸 넘기는 순간 시계열이 폭증한다. 기본값도 두지 않는다 —
     * 새 호출처가 조용히 "기타" 버킷으로 흘러들면 어느 기능이 토큰을 태우는지 다시 알 수 없다.
     */
    public enum CallSite {
        /** 회귀 감지 폴러의 1차 분석 (RegressionDetector) */
        REGRESSION,
        /** 웹 콘솔 실행계획 AI 분석 (InsightController) */
        EXPLAIN,
        /** 인시던트 리포트 요약 (IncidentReportService) */
        INCIDENT,
        /** 스키마 변경 리뷰 게이트 1차 소견 (ReviewService) */
        REVIEW,
        /** D3 자연어 근본원인 진단 루프 (DiagnosisService) — 1건이 여러 호출이라 비용 구조가 다르다 */
        DIAGNOSE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final String SYSTEM_PROMPT = """
            당신은 DB 운영 플랫폼의 1차 분석기다. 아래 회귀 감지 결과를 보고
            가장 유력한 원인과 확인할 것을 한국어 3~5문장으로 제시한다.
            반드시 아래 판단 기준 문서에 근거해서만 판정하고, 근거가 없으면 모른다고 말한다.
            수치를 지어내지 않는다.

            [판단 기준 문서]
            """;

    private final MeterRegistry meterRegistry;
    private final String model;
    private final Path rulesPath;
    private final long maxTokens;
    private final String effort;
    private final Mode mode;
    private volatile AnthropicClient client;

    public AiAnalyzer(MeterRegistry meterRegistry,
                      @Value("${dbtower.ai.model:claude-opus-4-8}") String model,
                      @Value("${dbtower.ai.rules-path:docs/ai-analysis-rules.md}") String rulesPath,
                      // adaptive thinking이 켜져 있어 이 예산은 "추론 + 응답"의 합산 상한이다.
                      // 너무 낮으면 추론이 예산을 먹고 응답이 중간에서 잘린다(D3 루프가 그걸 형식 오류로 오인).
                      @Value("${dbtower.ai.max-tokens:8192}") long maxTokens,
                      // 추론 깊이(low~max). 배포별 튜닝 손잡이다.
                      // 기본은 high — 낮추면 싸질 것으로 봤으나 실측에서 근거가 나오지 않았다.
                      // D3 진단 1건 기준 medium이 high 대비 -1.4%(출력 2782 vs 2796 토큰)로 노이즈 수준이라,
                      // 근거 없이 기본 동작을 바꾸지 않는다(VERIFICATION 121절).
                      @Value("${dbtower.ai.effort:high}") String effort) {
        this.meterRegistry = meterRegistry;
        this.model = model;
        this.rulesPath = Path.of(rulesPath);
        this.maxTokens = maxTokens;
        this.effort = effort;
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

    public Optional<String> analyze(CallSite callSite, String findingContext) {
        return complete(callSite, SYSTEM_PROMPT + loadRules(), findingContext);
    }

    /**
     * 임의의 시스템 프롬프트 + 사용자 메시지로 1회 완성 호출 — 백엔드(API/CLI)를 추상화한다.
     * D3 도구 사용 루프가 한 스텝(다음에 어떤 도구를 부를지 결정)마다 이걸 부른다.
     * 실패·시간초과는 예외를 던지지 않고 빈 값으로 정직하게 내려간다.
     */
    public Optional<String> complete(CallSite callSite, String systemPrompt, String userMessage) {
        if (mode == Mode.OFF) {
            return Optional.empty();
        }
        try {
            String text = switch (mode) {
                case API -> callApi(callSite, systemPrompt, userMessage);
                case CLI -> callCli(callSite, systemPrompt, userMessage);
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
        // 파일 부재를 조용히 빈 문자열로 넘기면 "반드시 아래 판단 기준 문서에 근거해서만 판정하라"는
        // 시스템 프롬프트 뒤가 텅 빈 채로 모델에 나간다 — AI가 판단자가 아니라 1차 분석기라는
        // 이 프로젝트의 원칙이 아무 신호 없이 무력화되는 경로다. 경로가 상대경로라 JAR을 다른
        // 디렉터리에서 띄우면 실제로 재현된다. 읽기 예외와 똑같이 경고를 남긴다.
        if (!Files.exists(rulesPath)) {
            log.warn("판단 기준 문서 없음 path={} — AI 분석이 판단 기준 없이 수행된다(dbtower.ai.rules-path 확인)",
                    rulesPath.toAbsolutePath());
            return "";
        }
        try {
            return Files.readString(rulesPath);
        } catch (Exception e) {
            log.warn("판단 기준 문서 로드 실패 path={}: {}", rulesPath.toAbsolutePath(), e.getMessage());
            return "";
        }
    }

    private String callApi(CallSite callSite, String system, String user) {
        Message message = client().messages().create(buildParams(model, maxTokens, effort, system, user));
        var usage = message.usage();
        recordTokens(meterRegistry, backend(), callSite,
                usage.inputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                usage.outputTokens());
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
     * API 요청 조립 — 시스템 프롬프트에 캐시 브레이크포인트를 건다.
     *
     * <p>{@code .system(String)} 오버로드는 cache control을 실을 수 없어 블록 형태로 넘긴다.
     * 시스템 프롬프트(판단 기준 문서 + 도구 카탈로그)는 호출 간 불변이라 두 번째 호출부터 읽기
     * 요금(정가의 10분의 1)으로 받는다. 휘발성 값을 여기 섞으면 프리픽스가 깨져 무효가 되므로
     * 대상·시각은 사용자 메시지에 싣는다(DiagnosisService 참고).
     *
     * <p>Spring 없이도 테스트 가능하도록 static — 네트워크 없이 조립 자체를 검증한다.
     */
    static MessageCreateParams buildParams(String model, long maxTokens, String effort,
                                           String system, String user) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(effort))
                        .build())
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(system)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()))
                .addUserMessage(user)
                .build();
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
     * 두 백엔드 공통 토큰 계수 — API·CLI가 같은 이름·같은 태그로 올려야 눈금이 비교된다(118절 원칙).
     *
     * <p>경고가 아니라 계수인 이유: 캐시 파손은 단일 호출로 판정할 수 없다. 진단 1건 안에서는 시스템
     * 프롬프트가 지역 변수라 애초에 바뀔 수 없고(그래서 파손이 있어도 2번째 호출부터는 캐시가 걸린다),
     * 진단과 진단 사이의 {@code cache_read=0}은 TTL이 지나면 정당하다. 파손은
     * {@code cache_read / (cache_read + cache_write)} 비율이 무너지는 것으로만 보인다.
     *
     * <p>순수 함수라 API 키·네트워크 없이 검증된다(buildParams와 같은 이유).
     */
    static void recordTokens(MeterRegistry registry, String backend, CallSite callSite,
                             long input, long cacheWrite, long cacheRead, long output) {
        count(registry, backend, callSite, "input", input);
        count(registry, backend, callSite, "cache_write", cacheWrite);
        count(registry, backend, callSite, "cache_read", cacheRead);
        count(registry, backend, callSite, "output", output);
    }

    /** 0도 증가시킨다 — 시계열이 존재해야 비율 쿼리가 성립한다(없는 시계열은 rate()가 비운다). */
    private static void count(MeterRegistry registry, String backend, CallSite callSite,
                              String type, long tokens) {
        registry.counter(METRIC, "backend", backend, "call_site", callSite.tag(), "type", type)
                .increment(Math.max(0, tokens));
    }

    /**
     * claude CLI headless 호출 — 프롬프트는 argv가 아니라 stdin으로 전달한다
     * (SQL·실행계획에 어떤 문자가 와도 인자 파싱과 무관하게 안전).
     */
    private String callCli(CallSite callSite, String system, String user) throws Exception {
        // --setting-sources "": 사용자/프로젝트 설정(출력 스타일 등)을 배제해
        // 어떤 로컬 환경에서도 같은 형식의 결과가 나오게 한다.
        // --output-format json: 본문만이 아니라 usage·stop_reason이 실린 봉투를 받는다 —
        // 구독(CLI) 경로에도 API 경로와 같은 관측점을 두어야 캐시·절단을 짝비교로 잴 수 있다.
        // --effort: API 경로의 outputConfig.effort와 같은 손잡이를 구독 경로에도 둔다(두 경로 동일 설정)
        Process p = new ProcessBuilder("claude", "-p", "--output-format", "json",
                "--effort", effort, "--setting-sources", "", "--append-system-prompt", system)
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
        return extractCliResult(out, env -> {
            JsonNode u = env.path("usage");
            recordTokens(meterRegistry, backend(), callSite,
                    u.path("input_tokens").asLong(),
                    u.path("cache_creation_input_tokens").asLong(),
                    u.path("cache_read_input_tokens").asLong(),
                    u.path("output_tokens").asLong());
        });
    }

    /** 봉투 파싱만 검증하는 경로(테스트) — 계수는 올리지 않는다. */
    static String extractCliResult(String raw) {
        return extractCliResult(raw, env -> { });
    }

    /**
     * claude CLI의 JSON 봉투에서 본문을 꺼내고, 그 김에 usage·절단을 관측한다.
     *
     * <p>봉투가 아니면(구버전 CLI·형식 변경) 평문으로 간주해 그대로 올린다 — 형식이 바뀌었다고
     * 진단 자체가 죽으면 안 된다. Spring 없이도 테스트 가능하도록 static.
     *
     * @param usageSink 봉투에서 usage를 꺼내 계수를 올리는 훅 — 절단 예외보다 <em>먼저</em> 불린다.
     *                  잘린 응답도 토큰은 이미 청구됐으므로 계수에서 빠지면 안 된다.
     */
    static String extractCliResult(String raw, Consumer<JsonNode> usageSink) {
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
        usageSink.accept(env);
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
