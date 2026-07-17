package io.dbtower.alert.internal;

import io.dbtower.alert.AlertMessageIndex;
import io.dbtower.alert.AlertMuter;
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
import java.util.List;

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

    /** Discord 알림 메시지 id ↔ 인스턴스 매핑 인덱스(Gateway 봇 이모지 트리거용). 테스트에선 null 가능. */
    private final AlertMessageIndex messageIndex;
    /** 인스턴스별 알림 일시 중지("알람 스킵") — 강제 지점은 여기 한 곳. 테스트에선 null 가능. */
    private final AlertMuter muter;

    public WebhookNotifier(@Value("${DBTOWER_WEBHOOK_URL:}") String webhookUrl,
                           @Value("${dbtower.alert.rate-per-minute:12}") int ratePerMinute,
                           AlertMessageIndex messageIndex, AlertMuter muter) {
        this.webhookUrl = webhookUrl;
        this.ratePerMinute = Math.max(1, ratePerMinute);
        this.messageIndex = messageIndex;
        this.muter = muter;
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
    public void send(String message) {
        sendAt(message, System.currentTimeMillis());
    }

    /**
     * 리치 embed 알림 (구조화 문의 카드). Discord는 embeds 페이로드로, Slack·미설정은 fallbackText로
     * 보낸다 — 웹훅도 이기종이라 "잘 꾸며주는 쪽"과 "확실히 도착하는 쪽"을 URL이 결정한다.
     * 레이트리밋은 텍스트 경로와 같은 윈도우를 쓴다(embed라고 도배가 허용되는 건 아니므로).
     */
    public void sendEmbed(String fallbackText, Embed embed) {
        sendEmbed(fallbackText, (Long) null, embed);
    }

    /**
     * instanceId를 함께 주면 Discord 발사 후 그 메시지 id를 인스턴스에 매핑해둔다(Gateway 봇이 반응 때
     * 특권 인텐트 없이 대상 인스턴스를 조회하게 — AlertMessageIndex 참고). null이면 매핑 안 함(문의 등).
     * (fallback, instanceId, embed) 인자 순서 — 호출부 가독성(맥락 → 페이로드).
     */
    public void sendEmbed(String fallbackText, Long instanceId, Embed embed) {
        sendEmbedAt(fallbackText, embed, instanceId, System.currentTimeMillis());
    }

    void sendEmbedAt(String fallbackText, Embed embed, Long instanceId, long now) {
        // 알람 스킵(음소거 반응) — 중지된 인스턴스의 알림은 발사하지 않는다. 조용한 유실이 아니라
        // 사람이 방금 명시적으로 끈 상태이고, 만료되면 자동 재개된다(레이트리밋 억제와 달리 요약도 안 남긴다).
        if (muter != null && muter.isMuted(instanceId)) {
            log.info("알림 중지 중(알람 스킵) — instance={} 발사 생략", instanceId);
            return;
        }
        String decided = decide(fallbackText, now);
        if (decided == null) {
            return;
        }
        // decide가 억제 요약을 덧붙였다면 그 꼬리를 분리해 embed에는 별도 알림줄로 싣는다
        String note = decided.length() > fallbackText.length()
                ? decided.substring(fallbackText.length()).strip() : "";
        deliverEmbed(decided, note, embed, instanceId);
    }

    /** embed 한 장 — 제목·색·필드 목록. 필드 value가 비면 페이로드에서 제외된다. */
    public record Embed(String title, int color, List<Field> fields) {
        public record Field(String name, String value, boolean inline) {
        }
    }

    void sendAt(String message, long now) {
        // B-5: 레이트리밋 판정(윈도우·suppressed 갱신 + 보낼 메시지 결정)만 락 안에서 하고,
        // 실제 전송(deliver = HTTP, 최대 ~8s)은 락 밖에서 한다. 락 안에서 HTTP를 하면 웹훅이 느릴 때
        // 모든 폴러 send가 직렬 블로킹돼 알림 폭주 시 폴러까지 동반 지연된다.
        String toDeliver = decide(message, now);
        // 락을 놓은 뒤 전송 — non-null이면 이번에 보낼 메시지(억제 요약이 붙어 있을 수 있다).
        // (여전히 호출 스레드에서 동기로 보내므로 기존 단위 테스트의 deliver 관측은 그대로 유지된다.)
        if (toDeliver != null) {
            deliver(toDeliver);
        }
    }

    /**
     * 레이트리밋 판정(배타) — 보낼 메시지(억제 요약 포함)를 돌려주거나, 상한 초과면 null.
     * 슬라이딩 윈도우·suppressed·sentWindow는 여러 폴러 스레드가 공유하는 상태라 이 판정만 배타화한다.
     */
    private synchronized String decide(String message, long now) {
        // 윈도우에서 60초 지난 전송 기록 제거
        while (!sentWindow.isEmpty() && now - sentWindow.peekFirst() > WINDOW_MS) {
            sentWindow.pollFirst();
        }
        if (sentWindow.size() >= ratePerMinute) {
            suppressed++;
            log.warn("알림 레이트리밋 초과 — 억제 {}건(분당 {}건 상한, 다음 허용 알림에 합산)", suppressed, ratePerMinute);
            return null;
        }
        String out = message;
        if (suppressed > 0) {
            out = message + "\n(그동안 억제된 알림 " + suppressed + "건 더 발생 — 대시보드 확인)";
            suppressed = 0;
        }
        sentWindow.addLast(now);
        return out;
    }

    /**
     * embed 실제 전송(package-private — 테스트에서 오버라이드해 관측).
     * Discord가 아니면(슬랙 등) embed 문법이 없으므로 textFallback(억제 요약 포함)을 그대로 보낸다.
     */
    void deliverEmbed(String textFallback, String suppressedNote, Embed embed, Long instanceId) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("[알림(webhook 미설정)] {}", textFallback);
            return;
        }
        boolean discord = webhookUrl.contains("discord.com");
        String payload = discord
                ? discordEmbedPayload(embed, suppressedNote)
                : "{\"text\": %s}".formatted(jsonString(textFallback));
        // instanceId가 있고 Discord면 ?wait=true로 메시지 id를 받아 인덱스에 기록(Gateway 봇 이모지 트리거).
        // 그 외엔 종전대로 fire-and-forget.
        if (discord && instanceId != null && messageIndex != null) {
            String id = postJsonReturningId(payload);
            if (id != null) {
                messageIndex.record(id, instanceId);
            }
        } else {
            postJson(payload);
        }
    }

    /** ?wait=true로 전송해 응답에서 메시지 id를 파싱한다(매핑 기록용). 실패는 null. */
    private String postJsonReturningId(String payload) {
        try {
            String url = webhookUrl + (webhookUrl.contains("?") ? "&" : "?") + "wait=true";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("웹훅 발송 실패 status={} body={}", res.statusCode(), res.body());
                return null;
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.body()).path("id").asText(null);
        } catch (Exception e) {
            log.warn("웹훅 발송 실패(id 조회): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Discord embeds 페이로드. 한도(제목 256자·필드 값 1024자·필드 25개)를 여기 경계에서 자른다 —
     * 호출자가 한도를 몰라도 전송이 4xx로 죽지 않게. allowed_mentions는 텍스트 경로와 같은 이유로 잠근다.
     */
    String discordEmbedPayload(Embed embed, String suppressedNote) {
        StringBuilder fields = new StringBuilder();
        int count = 0;
        for (Embed.Field f : embed.fields()) {
            if (f.value() == null || f.value().isBlank() || count >= 25) {
                continue;
            }
            if (count > 0) {
                fields.append(',');
            }
            fields.append("{\"name\": ").append(jsonString(clip(f.name(), 256)))
                  .append(", \"value\": ").append(jsonString(clip(f.value(), 1024)))
                  .append(", \"inline\": ").append(f.inline()).append('}');
            count++;
        }
        String content = suppressedNote == null || suppressedNote.isBlank()
                ? "" : "\"content\": %s, ".formatted(jsonString(suppressedNote));
        return "{%s\"embeds\": [{\"title\": %s, \"color\": %d, \"fields\": [%s]}], \"allowed_mentions\": {\"parse\": []}}"
                .formatted(content, jsonString(clip(embed.title(), 256)), embed.color(), fields);
    }

    /** Discord 한도 초과분을 자르고 잘렸음을 표시한다 — 조용한 절단은 "왜 뒷부분이 없지"를 만든다. */
    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 8) + "… (잘림)";
    }

    /** 실제 웹훅 전송(package-private — 레이트리밋 판정만 떼어 테스트할 때 오버라이드해 관측한다). */
    void deliver(String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("[알림(webhook 미설정)] {}", message);
            return;
        }
        // B-9: Discord는 allowed_mentions.parse=[]로 @everyone/@here·역할 멘션 인젝션을 원천 차단한다
        // (사용자 텍스트가 content로 그대로 들어가므로, 명시적으로 "어떤 멘션도 해석하지 말라"고 못 박는다).
        String payload = webhookUrl.contains("discord.com")
                ? "{\"content\": %s, \"allowed_mentions\": {\"parse\": []}}".formatted(jsonString(message))  // Discord 포맷
                : "{\"text\": %s}".formatted(jsonString(message));     // Slack 포맷
        postJson(payload);
    }

    private void postJson(String payload) {
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

    /**
     * JSON 문자열 리터럴로 이스케이프한다. B-9: \n만 처리하던 것을 확장해 역슬래시·따옴표·모든 제어문자
     * (\r·\t·\b·\f 및 그 밖의 U+0000..U+001F)를 안전하게 이스케이프한다 — 제어문자가 날것으로 들어가면
     * 페이로드 JSON이 깨지거나(전송 실패) \r 등이 주입 벡터가 될 수 있다.
     */
    private String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c)); // 그 밖의 제어문자
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
