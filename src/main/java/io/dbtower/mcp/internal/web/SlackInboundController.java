package io.dbtower.mcp.internal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.mcp.internal.DiagnosisService;
import io.dbtower.mcp.internal.DiscordTriggerRules;
import io.dbtower.mcp.internal.SlackSignatureVerifier;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Slack Events 인바운드 — 레퍼런스의 원 채널(Slack 알럿 스레드 이모지 → AI 분석 댓글) 대응.
 * Discord Gateway 봇과 같은 루프의 Slack 변형이다: reaction_added(돋보기)가 오면 그 메시지의
 * 알림 본문에서 인스턴스를 찾아 자연어 진단을 돌리고 스레드 답글로 붙인다.
 *
 * 보안은 Discord 인바운드와 같은 3단계 — (1) 서명 검증(v0 HMAC, 5분 리플레이 창),
 * (2) 채널 화이트리스트(기본 거부), (3) 유저 화이트리스트(기본 거부). signing secret 미설정이면
 * 404(기능 게이트 — 엔드포인트 존재 은닉). Slack의 3초 응답 규칙에 맞춰 이벤트는 즉시 200을 주고
 * 진단은 워커에서 돈다(재전송 폭주 방지).
 *
 * 정직한 검증 범위: 실 워크스페이스 미보유라 로컬 서명 시뮬레이션(challenge/서명/거부/진단 위임)까지가
 * 실검증이고, 실 Slack 발사는 워크스페이스 등록이 생기면 이 엔드포인트 그대로 연결된다(88절과 같은 모델).
 */
@RestController
public class SlackInboundController {

    private static final Logger log = LoggerFactory.getLogger(SlackInboundController.class);
    /** 알림 텍스트 폴백에서 인스턴스를 찾는 패턴 — "인스턴스: name (TYPE)" 줄(웹훅 포맷 계약). */
    private static final Pattern INSTANCE_LINE = Pattern.compile("인스턴스: (\\S+)");

    private final DiagnosisService diagnosisService;
    private final RegistryService registryService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dbtower-slack-inbound");
        t.setDaemon(true);
        return t;
    });

    private final String signingSecret;
    private final String botToken;
    private final Set<String> triggerEmojis;
    private final Set<String> channelAllowlist;
    private final Set<String> userAllowlist;

    public SlackInboundController(DiagnosisService diagnosisService, RegistryService registryService,
                                  @Value("${dbtower.inbound.slack.signing-secret:}") String signingSecret,
                                  @Value("${dbtower.inbound.slack.bot-token:}") String botToken,
                                  @Value("${dbtower.inbound.slack.trigger-emoji:mag,mag_right}") String triggerEmoji,
                                  @Value("${dbtower.inbound.slack.channel-allowlist:}") String channels,
                                  @Value("${dbtower.inbound.slack.user-allowlist:}") String users) {
        this.diagnosisService = diagnosisService;
        this.registryService = registryService;
        this.signingSecret = signingSecret == null ? "" : signingSecret.trim();
        this.botToken = botToken == null ? "" : botToken.trim();
        this.triggerEmojis = csv(triggerEmoji);
        this.channelAllowlist = csv(channels);
        this.userAllowlist = csv(users);
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    @PostMapping("/api/inbound/slack")
    public ResponseEntity<String> receive(@RequestBody String body,
                                          @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
                                          @RequestHeader(value = "X-Slack-Signature", required = false) String signature) {
        if (signingSecret.isBlank()) {
            return ResponseEntity.notFound().build(); // 기능 게이트 — 미설정이면 존재 은닉
        }
        if (!SlackSignatureVerifier.verify(signingSecret, timestamp, body, signature,
                Instant.now().getEpochSecond())) {
            return ResponseEntity.status(401).build();
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        // URL 등록 시 Slack이 보내는 소유 확인 — challenge를 그대로 되돌린다
        if ("url_verification".equals(root.path("type").asText())) {
            return ResponseEntity.ok(root.path("challenge").asText());
        }
        JsonNode event = root.path("event");
        if ("reaction_added".equals(event.path("type").asText())) {
            String emoji = event.path("reaction").asText();
            String channel = event.path("item").path("channel").asText();
            String user = event.path("user").asText();
            String ts = event.path("item").path("ts").asText();
            if (triggerEmojis.contains(emoji)
                    && DiscordTriggerRules.allowed(channelAllowlist, channel)
                    && DiscordTriggerRules.allowed(userAllowlist, user)) {
                worker.execute(() -> diagnoseAndReply(channel, ts));
            }
        }
        return ResponseEntity.ok(""); // 3초 규칙 — 즉시 ACK, 처리는 워커
    }

    /** 반응 달린 알림 메시지를 읽어(conversations.history) 인스턴스를 찾고 스레드 답글로 진단을 붙인다. */
    private void diagnoseAndReply(String channel, String ts) {
        try {
            String text = fetchMessageText(channel, ts);
            Matcher m = text == null ? null : INSTANCE_LINE.matcher(text);
            if (m == null || !m.find()) {
                postThread(channel, ts, "이 메시지에서 대상 인스턴스를 알 수 없어 진단을 시작하지 못했습니다.");
                return;
            }
            String name = m.group(1);
            DatabaseInstance instance = registryService.findAll().stream()
                    .filter(i -> i.getName().equals(name)).findFirst().orElse(null);
            if (instance == null) {
                postThread(channel, ts, "등록되지 않은 인스턴스입니다: " + name);
                return;
            }
            postThread(channel, ts, instance.getName() + " 진단을 시작합니다… (잠시만요)");
            var result = diagnosisService.diagnose(instance.getId(), instance.getType().name(),
                    instance.getName(), "방금 이 알림이 온 이유를 분석해줘");
            postThread(channel, ts, "[" + instance.getName() + "] " + result.answer());
        } catch (Exception e) {
            log.warn("Slack 반응 진단 실패 channel={} ts={} cause={}", channel, ts, e.getMessage());
        }
    }

    private String fetchMessageText(String channel, String ts) throws Exception {
        if (botToken.isBlank()) {
            return null; // 메시지 조회는 봇 토큰 필요 — 없으면 인스턴스 해소 불가를 정직히 안내
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        "https://slack.com/api/conversations.history?channel=" + channel
                                + "&latest=" + ts + "&limit=1&inclusive=true"))
                .header("Authorization", "Bearer " + botToken)
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode messages = mapper.readTree(res.body()).path("messages");
        return messages.isArray() && messages.size() > 0 ? messages.get(0).path("text").asText(null) : null;
    }

    private void postThread(String channel, String threadTs, String text) throws Exception {
        if (botToken.isBlank()) {
            log.info("[Slack 답글(봇 토큰 미설정)] {}", text);
            return;
        }
        String payload = mapper.writeValueAsString(Map.of(
                "channel", channel, "thread_ts", threadTs, "text", text));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://slack.com/api/chat.postMessage"))
                .header("Authorization", "Bearer " + botToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
