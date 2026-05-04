package io.dbtower.alert;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
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
 * 시스템 프롬프트로 넣어, 같은 입력에 일관된 판정이 나오게 한다. (당근 KDMS와 같은 접근)
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
    private final Mode mode;
    private volatile AnthropicClient client;

    public AiAnalyzer(@Value("${dbtower.ai.model:claude-opus-4-8}") String model,
                      @Value("${dbtower.ai.rules-path:docs/ai-analysis-rules.md}") String rulesPath) {
        this.model = model;
        this.rulesPath = Path.of(rulesPath);
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

    public Optional<String> analyze(String findingContext) {
        if (mode == Mode.OFF) {
            return Optional.empty();
        }
        try {
            String rules = Files.exists(rulesPath) ? Files.readString(rulesPath) : "";
            String text = switch (mode) {
                case API -> analyzeViaApi(rules, findingContext);
                case CLI -> analyzeViaCli(rules, findingContext);
                case OFF -> "";
            };
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
        } catch (Exception e) {
            // 분석 실패는 알림을 막지 않는다
            log.warn("AI 1차 분석 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String analyzeViaApi(String rules, String findingContext) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(2048L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT + rules)
                .addUserMessage(findingContext)
                .build();
        return client().messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .reduce("", String::concat);
    }

    /**
     * claude CLI headless 호출 — 프롬프트는 argv가 아니라 stdin으로 전달한다
     * (SQL·실행계획에 어떤 문자가 와도 인자 파싱과 무관하게 안전).
     */
    private String analyzeViaCli(String rules, String findingContext) throws Exception {
        // --setting-sources "": 사용자/프로젝트 설정(출력 스타일 등)을 배제해
        // 어떤 로컬 환경에서도 같은 형식의 순수 분석 텍스트가 나오게 한다
        Process p = new ProcessBuilder("claude", "-p", "--setting-sources", "",
                "--append-system-prompt", SYSTEM_PROMPT + rules)
                .redirectErrorStream(false).start();
        try (var stdin = p.getOutputStream()) {
            stdin.write(findingContext.getBytes(StandardCharsets.UTF_8));
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
        return out;
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
