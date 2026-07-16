package io.dbtower.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Discord Gateway 봇 (심화 아크 5 2단계 — 레퍼런스의 "알럿 이모지 → AI 분석 댓글" 방식).
 *
 * 슬래시 커맨드(DiscordInboundController)가 "요청-응답"이라면, 이쪽은 채널의 <b>이벤트를 상시 수신</b>한다:
 * 우리 알림 메시지에 지정 이모지(기본 🔍)를 달면 → 봇이 그 알림의 대상 인스턴스를 알아내 자연어 진단을
 * 돌리고 → 그 메시지에 답글로 결과를 붙인다. 채널 계층 원칙 그대로 — 판정은 DiscordTriggerRules,
 * 진단은 DiagnosisService에 위임하고 여기선 Gateway 프로토콜과 배선만.
 *
 * 외부 라이브러리 없이 java.net.http.WebSocket으로 Gateway 프로토콜을 직접 구현한다(MCP JSON-RPC·
 * Ed25519를 손수 구현한 것과 같은 결):
 *  - HELLO(op 10)에서 heartbeat_interval을 받아 주기 하트비트(op 1) 시작
 *  - IDENTIFY(op 2)로 토큰·intents(GUILDS + GUILD_MESSAGE_REACTIONS — 반응만 보면 되므로 특권 인텐트
 *    MESSAGE_CONTENT는 안 쓴다) 전송
 *  - READY에서 봇 자신의 user id를 기억(자기 반응 무시)
 *  - MESSAGE_REACTION_ADD(op 0) 처리
 *  - 연결이 끊기면 재접속(단순화: RESUME 없이 재-IDENTIFY — 이 용도엔 세션 이어붙이기가 불필요)
 *
 * 게이트: 봇 토큰 미설정이면 연결 자체를 하지 않는다(기능 게이트). 반응 메시지 조회·답글은 REST로
 * 하며 봇 토큰을 "Bot {token}"으로 싣는다(특권 인텐트 없이도 채널 접근 권한만 있으면 조회된다).
 */
@Component
public class DiscordGatewayBot {

    private static final Logger log = LoggerFactory.getLogger(DiscordGatewayBot.class);

    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final String API = "https://discord.com/api/v10";
    // intents: GUILDS(1<<0) | GUILD_MESSAGE_REACTIONS(1<<10) = 1025 — 반응 이벤트만 받는다(특권 인텐트 불요)
    private static final int INTENTS = (1) | (1 << 10);

    private final RegistryService registryService;
    private final DiagnosisService diagnosisService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dbtower-discord-gateway");
        t.setDaemon(true);
        return t;
    });

    private final String botToken;
    private final String triggerEmoji;
    private final Set<String> channelAllowlist;
    private final Set<String> userAllowlist;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WebSocket socket;
    private volatile Integer lastSequence;
    private volatile String botUserId;
    private final StringBuilder frame = new StringBuilder();

    public DiscordGatewayBot(RegistryService registryService, DiagnosisService diagnosisService,
                             @Value("${dbtower.inbound.discord.bot-token:}") String botToken,
                             @Value("${dbtower.inbound.discord.trigger-emoji:🔍}") String triggerEmoji,
                             @Value("${dbtower.inbound.discord.channel-allowlist:}") String channels,
                             @Value("${dbtower.inbound.discord.user-allowlist:}") String users) {
        this.registryService = registryService;
        this.diagnosisService = diagnosisService;
        this.botToken = botToken == null ? "" : botToken.trim();
        this.triggerEmoji = triggerEmoji;
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

    @PostConstruct
    void start() {
        if (botToken.isBlank()) {
            return; // 기능 게이트 — 봇 토큰 없으면 Gateway 연결 안 함
        }
        running.set(true);
        connect();
        log.info("Discord Gateway 봇 시작 — 트리거 이모지 '{}' (알림에 이 이모지를 달면 진단이 붙는다)", triggerEmoji);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        scheduler.shutdownNow();
    }

    private void connect() {
        http.newWebSocketBuilder()
                .buildAsync(URI.create(GATEWAY_URL), new Listener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.warn("Discord Gateway 연결 실패 — 5초 후 재시도: {}", err.getMessage());
                        scheduleReconnect();
                    } else {
                        socket = ws;
                    }
                });
    }

    private void scheduleReconnect() {
        if (running.get()) {
            scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
        }
    }

    /** Gateway WebSocket 리스너 — 텍스트 프레임을 모아 완성된 JSON을 처리한다. */
    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frame.append(data);
            if (last) {
                String json = frame.toString();
                frame.setLength(0);
                try {
                    handle(webSocket, mapper.readTree(json));
                } catch (Exception e) {
                    log.warn("Gateway 메시지 처리 실패: {}", e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Discord Gateway 연결 종료 code={} reason={} — 재접속", statusCode, reason);
            socket = null;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Discord Gateway 오류: {}", error.getMessage());
            socket = null;
            scheduleReconnect();
        }
    }

    private void handle(WebSocket ws, JsonNode msg) {
        int op = msg.path("op").asInt();
        if (!msg.path("s").isNull() && msg.path("s").isNumber()) {
            lastSequence = msg.path("s").asInt();
        }
        switch (op) {
            case 10 -> { // HELLO — 하트비트 시작 + IDENTIFY
                long interval = msg.path("d").path("heartbeat_interval").asLong(45000);
                scheduler.scheduleAtFixedRate(() -> sendHeartbeat(ws), interval, interval, TimeUnit.MILLISECONDS);
                identify(ws);
            }
            case 0 -> dispatch(msg.path("t").asText(), msg.path("d")); // 이벤트
            case 7, 9 -> { // RECONNECT / INVALID_SESSION — 재접속
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
            }
            default -> { /* 11=heartbeat ACK 등 — 무시 */ }
        }
    }

    private void sendHeartbeat(WebSocket ws) {
        String seq = lastSequence == null ? "null" : lastSequence.toString();
        ws.sendText("{\"op\":1,\"d\":" + seq + "}", true);
    }

    private void identify(WebSocket ws) {
        String payload = """
                {"op":2,"d":{"token":%s,"intents":%d,"properties":{"os":"linux","browser":"dbtower","device":"dbtower"}}}"""
                .formatted(jsonString(botToken), INTENTS);
        ws.sendText(payload, true);
    }

    private void dispatch(String type, JsonNode d) {
        if ("READY".equals(type)) {
            botUserId = d.path("user").path("id").asText();
            log.info("Discord Gateway READY — 봇 user={}", botUserId);
            return;
        }
        if ("MESSAGE_REACTION_ADD".equals(type)) {
            String emoji = d.path("emoji").path("name").asText(null);
            String channelId = d.path("channel_id").asText();
            String messageId = d.path("message_id").asText();
            String userId = d.path("user_id").asText();
            if (DiscordTriggerRules.shouldReact(emoji, triggerEmoji, channelId, userId, botUserId,
                    channelAllowlist, userAllowlist)) {
                // 진단은 느리다 — 스케줄러 스레드를 막지 않게 워커로 넘긴다
                scheduler.execute(() -> diagnoseAndReply(channelId, messageId));
            }
        }
    }

    /** 반응이 달린 알림 메시지를 조회해 대상 인스턴스를 알아내고, 진단 결과를 답글로 붙인다. */
    private void diagnoseAndReply(String channelId, String messageId) {
        try {
            JsonNode message = restGet("/channels/" + channelId + "/messages/" + messageId);
            if (message == null) {
                return;
            }
            String content = message.path("content").asText("");
            String embedTitle = message.path("embeds").isArray() && message.path("embeds").size() > 0
                    ? message.path("embeds").get(0).path("title").asText(null) : null;
            String instanceName = DiscordTriggerRules.extractInstanceName(embedTitle, content);
            if (instanceName == null) {
                reply(channelId, messageId, "이 메시지에서 대상 인스턴스를 알 수 없어 진단을 시작하지 못했습니다.");
                return;
            }
            DatabaseInstance instance = registryService.findAll().stream()
                    .filter(i -> i.getName().equals(instanceName))
                    .findFirst().orElse(null);
            if (instance == null) {
                reply(channelId, messageId, "등록되지 않은 인스턴스: " + instanceName);
                return;
            }
            reply(channelId, messageId, "**" + instanceName + "** 진단을 시작합니다… (잠시만요)");
            var result = diagnosisService.diagnose(instance.getId(), instance.getType().name(),
                    instance.getName(), "방금 이 알림이 온 이유를 분석해줘");
            reply(channelId, messageId, clip("[" + instanceName + "] " + result.answer()));
        } catch (Exception e) {
            log.warn("Discord 반응 진단 실패 channel={} msg={} cause={}", channelId, messageId, e.getMessage());
        }
    }

    /** Discord 메시지 본문 한도(2000자) — 초과분은 경계에서 자른다(문의 embed 절단과 같은 규칙). */
    private static String clip(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 1980) + "\n…(한도 절단)";
    }

    private JsonNode restGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + botToken)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            log.warn("Discord REST GET 실패 path={} status={}", path, res.statusCode());
            return null;
        }
        return mapper.readTree(res.body());
    }

    /** 원본 메시지에 답글(message_reference)로 붙인다 — 레퍼런스의 "스레드 댓글"에 대응. */
    private void reply(String channelId, String messageId, String content) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "content", content,
                    "message_reference", Map.of("message_id", messageId),
                    "allowed_mentions", Map.of("parse", java.util.List.of())));
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/channels/" + channelId + "/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("Discord 답글 실패 status={} body={}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            log.warn("Discord 답글 발송 실패: {}", e.getMessage());
        }
    }

    /** JSON 문자열 리터럴 이스케이프(토큰에 특수문자가 있어도 안전) — 최소 구현. */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
