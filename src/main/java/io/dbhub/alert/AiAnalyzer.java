package io.dbhub.alert;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 회귀 감지 결과에 대한 AI 1차 분석 (확장3).
 *
 * 판단을 통째로 LLM에 맡기지 않는다 — docs/ai-analysis-rules.md의 기종별 판단 기준을
 * 시스템 프롬프트로 넣어, 같은 입력에 일관된 판정이 나오게 한다. (당근 KDMS와 같은 접근)
 * API 키가 없으면 조용히 비활성화되고, 분석 실패가 알림 자체를 막지 않는다.
 */
@Component
public class AiAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AiAnalyzer.class);

    private final String model;
    private final Path rulesPath;
    private final boolean enabled;
    private volatile AnthropicClient client;

    public AiAnalyzer(@Value("${dbhub.ai.model:claude-opus-4-8}") String model,
                      @Value("${dbhub.ai.rules-path:docs/ai-analysis-rules.md}") String rulesPath) {
        this.model = model;
        this.rulesPath = Path.of(rulesPath);
        String key = System.getenv("ANTHROPIC_API_KEY");
        this.enabled = key != null && !key.isBlank();
        if (!enabled) {
            log.info("ANTHROPIC_API_KEY 미설정 — AI 1차 분석 비활성화 (규칙 기반 알림만 발송)");
        }
    }

    public Optional<String> analyze(String findingContext) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String rules = Files.exists(rulesPath) ? Files.readString(rulesPath) : "";
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(2048L)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .system("""
                            당신은 DB 운영 플랫폼의 1차 분석기다. 아래 회귀 감지 결과를 보고
                            가장 유력한 원인과 확인할 것을 한국어 3~5문장으로 제시한다.
                            반드시 아래 판단 기준 문서에 근거해서만 판정하고, 근거가 없으면 모른다고 말한다.
                            수치를 지어내지 않는다.

                            [판단 기준 문서]
                            """ + rules)
                    .addUserMessage(findingContext)
                    .build();
            String text = client().messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .reduce("", String::concat);
            return text.isBlank() ? Optional.empty() : Optional.of(text.trim());
        } catch (Exception e) {
            // 분석 실패는 알림을 막지 않는다
            log.warn("AI 1차 분석 실패: {}", e.getMessage());
            return Optional.empty();
        }
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
