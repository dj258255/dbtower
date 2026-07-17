package io.dbtower.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.alert.AlertMessageIndex;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private final AlertMessageIndex messageIndex;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dbtower-discord-gateway");
        t.setDaemon(true);
        return t;
    });
    // 진단은 분 단위로 오래 걸린다 — 하트비트와 같은 스레드에 태우면 하트비트가 굶어 Discord가
    // 연결을 끊는다(실측: 진단 중 code=1000 재접속 반복). 진단 전용 워커로 분리한다(직렬이면 충분).
    private final ExecutorService diagnosisWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dbtower-discord-diagnosis");
        t.setDaemon(true);
        return t;
    });

    private final String botToken;
    private final Set<String> triggerEmojis;
    private final boolean allowSelfReact;
    private final Set<String> channelAllowlist;
    private final Set<String> userAllowlist;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WebSocket socket;
    private volatile Integer lastSequence;
    private volatile String botUserId;
    /** 현재 연결의 하트비트 태스크 — 재접속 시 취소하지 않으면 연결마다 하트비트가 누적된다. */
    private volatile ScheduledFuture<?> heartbeatTask;
    private final StringBuilder frame = new StringBuilder();
    /** 진단·답글을 이미 한 메시지 id — 재접속 보충 스캔·중복 반응에서 두 번 처리하지 않게(멱등). */
    private final Set<String> processed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DiscordGatewayBot(RegistryService registryService, DiagnosisService diagnosisService,
                             AlertMessageIndex messageIndex,
                             @Value("${dbtower.inbound.discord.bot-token:}") String botToken,
                             @Value("${dbtower.inbound.discord.trigger-emoji:🔍,🔎}") String triggerEmoji,
                             @Value("${dbtower.inbound.discord.channel-allowlist:}") String channels,
                             @Value("${dbtower.inbound.discord.user-allowlist:}") String users,
                             @Value("${dbtower.inbound.discord.allow-self-react:false}") boolean allowSelfReact) {
        this.registryService = registryService;
        this.diagnosisService = diagnosisService;
        this.messageIndex = messageIndex;
        this.botToken = botToken == null ? "" : botToken.trim();
        // 왼쪽(🔍 U+1F50D)·오른쪽(🔎 U+1F50E) 돋보기는 다른 유니코드 — 둘 다 트리거로 받는다(실측 함정)
        this.triggerEmojis = csv(triggerEmoji);
        this.channelAllowlist = csv(channels);
        this.userAllowlist = csv(users);
        this.allowSelfReact = allowSelfReact; // 테스트 훅 — true면 봇 자기 반응도 처리(전체 파이프라인 실증용, 기본 false)
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
        log.info("Discord Gateway 봇 시작 — 트리거 이모지 {} (알림에 이 이모지를 달면 진단이 붙는다)", triggerEmojis);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        scheduler.shutdownNow();
        diagnosisWorker.shutdownNow();
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
                ScheduledFuture<?> prev = heartbeatTask;
                if (prev != null) {
                    prev.cancel(false); // 이전 연결의 하트비트 중단 — 안 하면 재접속마다 누적된다
                }
                heartbeatTask = scheduler.scheduleAtFixedRate(() -> sendHeartbeat(ws), interval, interval, TimeUnit.MILLISECONDS);
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
            // 연결 시 보충 스캔 — Gateway는 연결 중의 이벤트만 주므로(재접속/기동 전 반응은 재생 안 됨),
            // 화이트리스트 채널의 최근 메시지에 이미 달린 트리거 반응을 한 번 훑어 처리한다(멱등: 처리한 메시지 기억).
            diagnosisWorker.execute(this::reconcileExistingReactions);
            return;
        }
        if ("MESSAGE_REACTION_ADD".equals(type)) {
            String emoji = d.path("emoji").path("name").asText(null);
            String channelId = d.path("channel_id").asText();
            String messageId = d.path("message_id").asText();
            String userId = d.path("user_id").asText();
            // allowSelfReact(테스트 훅)면 봇 자기 반응 배제를 끈다 — botUserId를 null로 넘겨 우회.
            if (DiscordTriggerRules.shouldReact(emoji, triggerEmojis, channelId, userId,
                    allowSelfReact ? null : botUserId, channelAllowlist, userAllowlist)) {
                // 진단은 느리다 — 하트비트가 도는 스케줄러가 아니라 진단 전용 워커로 넘긴다
                diagnosisWorker.execute(() -> diagnoseAndReply(channelId, messageId, true));
            } else {
                // 트리거·화이트리스트에 안 걸린 반응 — 왜 무시됐는지 추적 가능하게 남긴다(설정 디버깅)
                log.debug("반응 무시 emoji={} channel={} user={} (트리거·화이트리스트 밖)", emoji, channelId, userId);
            }
        }
    }

    /**
     * 연결 시 보충 스캔 — 화이트리스트 채널의 최근 메시지에서 트리거 이모지가 이미 달린 것을 찾아,
     * 그 이모지를 화이트리스트 유저가 달았는지 확인하고 처리한다. 재접속마다 도는데, processed로
     * 멱등을 보장해 같은 메시지를 두 번 진단하지 않는다(반응 이벤트 경로와도 공유).
     */
    private void reconcileExistingReactions() {
        for (String channelId : channelAllowlist) {
            try {
                JsonNode messages = restGet("/channels/" + channelId + "/messages?limit=25");
                if (messages == null || !messages.isArray()) {
                    continue;
                }
                for (JsonNode msg : messages) {
                    String messageId = msg.path("id").asText();
                    if (processed.contains(messageId)) {
                        continue;
                    }
                    for (JsonNode reaction : msg.path("reactions")) {
                        String emoji = reaction.path("emoji").path("name").asText(null);
                        if (emoji == null || !triggerEmojis.contains(emoji)) {
                            continue;
                        }
                        if (reactedByAllowedUser(channelId, messageId, emoji)) {
                            log.info("보충 스캔 — 기존 반응 {} 처리 message={}", emoji, messageId);
                            diagnoseAndReply(channelId, messageId, false);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("보충 스캔 실패 channel={} cause={}", channelId, e.getMessage());
            }
        }
    }

    /** 이 메시지의 이 이모지를 화이트리스트 유저가 달았는가 — 반응 이벤트가 없는 보충 경로에서 확인. */
    private boolean reactedByAllowedUser(String channelId, String messageId, String emoji) throws Exception {
        String enc = URLEncoder.encode(emoji, StandardCharsets.UTF_8);
        JsonNode users = restGet("/channels/" + channelId + "/messages/" + messageId + "/reactions/" + enc);
        if (users == null || !users.isArray()) {
            return false;
        }
        for (JsonNode u : users) {
            String uid = u.path("id").asText();
            if ((allowSelfReact || !uid.equals(botUserId)) && (DiscordTriggerRules.allowed(userAllowlist, uid) || (allowSelfReact && uid.equals(botUserId)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 반응이 달린 알림 메시지를 조회해 대상 인스턴스를 알아내고, 진단 결과를 답글로 붙인다.
     *
     * announceUnresolved — 대상 인스턴스를 못 알아냈을 때 안내 답글을 다는가. 라이브 반응(true)은
     * 사람이 방금 눌러 기다리고 있으니 침묵하면 안 되고, 재시작 보충 스캔(false)은 과거 이력 순회라
     * 재시작할 때마다 옛 알림에 같은 안내가 반복 답글로 쌓인다(스팸) — 로그만 남기고 넘어간다.
     */
    private void diagnoseAndReply(String channelId, String messageId, boolean announceUnresolved) {
        if (!processed.add(messageId)) {
            return; // 이미 처리한 메시지 — 중복 반응·재접속 보충에서 두 번 진단하지 않는다
        }
        // 처리 이력은 영속(V22)도 확인 — 인메모리만 보면 재시작 후 보충 스캔이 이미 답글 단
        // 알림을 다시 진단한다(실측). 매핑처럼 이력도 메타 DB가 진실이다.
        if (messageIndex.alreadyProcessed(messageId)) {
            log.info("반응 진단 건너뜀 — 이미 처리된 알림(영속 이력) message={}", messageId);
            return;
        }
        try {
            // 1차: 발사 시점 매핑(AlertMessageIndex) — 특권 인텐트 없이 대상 인스턴스를 바로 안다.
            Long mappedId = messageIndex.instanceFor(messageId);
            DatabaseInstance instance = null;
            if (mappedId != null) {
                instance = registryService.findAll().stream()
                        .filter(i -> i.getId().equals(mappedId)).findFirst().orElse(null);
            }
            // 2차 폴백: embed 제목 파싱 — 인덱스에서 밀려났거나(재시작·오래된 알림) 매핑이 없을 때.
            // 웹훅 embed를 읽으려면 Message Content 특권 인텐트가 필요하다(없으면 embed가 비어 실패).
            if (instance == null) {
                JsonNode message = restGet("/channels/" + channelId + "/messages/" + messageId);
                if (message == null) {
                    return;
                }
                String content = message.path("content").asText("");
                String embedTitle = message.path("embeds").isArray() && message.path("embeds").size() > 0
                        ? message.path("embeds").get(0).path("title").asText(null) : null;
                String instanceName = DiscordTriggerRules.extractInstanceName(embedTitle, content);
                if (instanceName == null) {
                    if (announceUnresolved) {
                        reply(channelId, messageId, "이 메시지에서 대상 인스턴스를 알 수 없어 진단을 시작하지 못했습니다 "
                                + "(오래된 알림이면 봇 재시작으로 매핑이 비었을 수 있습니다 — 새 알림에 반응해 주세요).");
                    } else {
                        log.info("보충 스캔 — 대상 인스턴스 미해소로 건너뜀 message={} (재시작으로 매핑 소실)", messageId);
                    }
                    return;
                }
                instance = registryService.findAll().stream()
                        .filter(i -> i.getName().equals(instanceName))
                        .findFirst().orElse(null);
            }
            if (instance == null) {
                if (announceUnresolved) {
                    reply(channelId, messageId, "등록되지 않은 인스턴스입니다.");
                } else {
                    log.info("보충 스캔 — 미등록 인스턴스로 건너뜀 message={}", messageId);
                }
                return;
            }
            String instanceName = instance.getName();
            reply(channelId, messageId, "**" + instanceName + "** 진단을 시작합니다… (잠시만요)");
            var result = diagnosisService.diagnose(instance.getId(), instance.getType().name(),
                    instanceName, "방금 이 알림이 온 이유를 분석해줘");
            replyEmbed(channelId, messageId, "DBTower 진단 — " + instanceName, result.answer());
            messageIndex.markProcessed(messageId); // 재시작 후에도 중복 진단 안 하게 이력 영속
        } catch (Exception e) {
            log.warn("Discord 반응 진단 실패 channel={} msg={} cause={}", channelId, messageId, e.getMessage());
        }
    }

    /** embed description 한도(4096자) 경계 절단 — 본문(2000자)보다 여유가 있어 긴 진단 답변에 알맞다. */
    private static String clipEmbed(String s) {
        return s.length() <= 4000 ? s : s.substring(0, 3980) + "\n…(한도 절단)";
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
        postReply(channelId, messageId, Map.of("content", content));
    }

    /**
     * 진단 결과 답글을 embed 카드로 — 감지 알림·문의와 같은 결(알림은 카드인데 답변만 밋밋한 텍스트면
     * 루프의 마지막 조각만 격이 떨어진다). 색은 문의와 같은 브랜드 인디고.
     */
    private void replyEmbed(String channelId, String messageId, String title, String description) {
        Map<String, Object> embed = Map.of(
                "title", title,
                "color", 0x6366F1,
                "description", clipEmbed(description));
        postReply(channelId, messageId, Map.of("embeds", List.of(embed)));
    }

    private void postReply(String channelId, String messageId, Map<String, Object> payload) {
        try {
            Map<String, Object> body = new HashMap<>(payload);
            body.put("message_reference", Map.of("message_id", messageId));
            body.put("allowed_mentions", Map.of("parse", List.of()));
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/channels/" + channelId + "/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("Discord 답글 실패 status={} body={}", res.statusCode(), res.body());
            } else {
                log.info("Discord 답글 발송 status={} reply_to={}", res.statusCode(), messageId);
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
