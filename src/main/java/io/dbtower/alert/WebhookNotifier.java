package io.dbtower.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 웹훅 알림 어댑터. 웹훅도 이기종이다 — URL을 보고 Discord/Slack 포맷을 고른다.
 * URL 미설정이면 로그로만 출력한다(개발 환경에서 알림 흐름을 확인할 수 있게).
 * URL은 비밀값이므로 환경변수(DBTOWER_WEBHOOK_URL)로만 주입한다.
 */
@Component
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private final String webhookUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public WebhookNotifier(@Value("${DBTOWER_WEBHOOK_URL:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void send(String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("[알림(webhook 미설정)] {}", message);
            return;
        }
        String payload = webhookUrl.contains("discord.com")
                ? "{\"content\": %s}".formatted(jsonString(message))   // Discord 포맷
                : "{\"text\": %s}".formatted(jsonString(message));     // Slack 포맷
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("웹훅 발송 실패 status={} body={}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            // 알림 실패가 감지 파이프라인을 멈추면 안 된다
            log.warn("웹훅 발송 실패: {}", e.getMessage());
        }
    }

    private String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
