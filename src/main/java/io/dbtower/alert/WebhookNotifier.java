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
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 웹훅 알림 어댑터. 웹훅도 이기종이다 — URL을 보고 Discord/Slack 포맷을 고른다.
 * URL 미설정이면 로그로만 출력한다(개발 환경에서 알림 흐름을 확인할 수 있게).
 * URL은 비밀값이므로 환경변수(DBTOWER_WEBHOOK_URL)로만 주입한다.
 *
 * <p>알림 폭주 제어 (Phase F, 스케일 제어): 인스턴스가 많아지거나 대량 장애가 나면 알림이 한꺼번에 터져
 * 채널을 도배하고 정작 중요한 신호가 묻힌다. 그래서 <b>분당 상한</b>(dbtower.alert.rate-per-minute)을 두고,
 * 초과분은 버리지 않고 <b>개수만 세었다가 다음 허용 알림에 "그동안 N건 더 발생" 한 줄로 합친다</b>
 * (정보는 안 잃고 채널은 안 막는 절충). 여러 폴러가 서로 다른 스케줄러 스레드에서 부르므로 send는 배타적이다.
 */
@Component
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    private static final long WINDOW_MS = 60_000;

    private final String webhookUrl;
    private final int ratePerMinute;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 최근 60초 안에 실제로 보낸 시각들(슬라이딩 윈도우) + 상한 초과로 억제된 건수. */
    private final Deque<Long> sentWindow = new ArrayDeque<>();
    private int suppressed = 0;

    public WebhookNotifier(@Value("${DBTOWER_WEBHOOK_URL:}") String webhookUrl,
                           @Value("${dbtower.alert.rate-per-minute:12}") int ratePerMinute) {
        this.webhookUrl = webhookUrl;
        this.ratePerMinute = Math.max(1, ratePerMinute);
    }

    /**
     * 웹훅이 설정돼 있는지 — 문의(InquiryService)가 "전송됨/미설정"을 응답으로 구분하기 위해 필요하다.
     * send()는 미설정이면 조용히 로그만 남기고 반환하므로, 호출자가 결과를 알 수 없어 이 질의를 연다.
     */
    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /**
     * 분당 상한을 적용해 보낸다. 상한 안이면 즉시 전송(억제분이 있으면 요약을 덧붙임), 초과면 억제 카운트만 올린다.
     * 순수 판정을 테스트할 수 있도록 시각(now)을 받는 패키지-프라이빗 오버로드로 분리한다.
     */
    public synchronized void send(String message) {
        sendAt(message, System.currentTimeMillis());
    }

    synchronized void sendAt(String message, long now) {
        // 윈도우에서 60초 지난 전송 기록 제거
        while (!sentWindow.isEmpty() && now - sentWindow.peekFirst() > WINDOW_MS) {
            sentWindow.pollFirst();
        }
        if (sentWindow.size() >= ratePerMinute) {
            suppressed++;
            log.warn("알림 레이트리밋 초과 — 억제 {}건(분당 {}건 상한, 다음 허용 알림에 합산)", suppressed, ratePerMinute);
            return;
        }
        String out = message;
        if (suppressed > 0) {
            out = message + "\n(그동안 억제된 알림 " + suppressed + "건 더 발생 — 대시보드 확인)";
            suppressed = 0;
        }
        sentWindow.addLast(now);
        deliver(out);
    }

    /** 실제 웹훅 전송(package-private — 레이트리밋 판정만 떼어 테스트할 때 오버라이드해 관측한다). */
    void deliver(String message) {
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
