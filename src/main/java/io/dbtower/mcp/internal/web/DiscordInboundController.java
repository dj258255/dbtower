package io.dbtower.mcp.internal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.mcp.internal.DiagnosisService;
import io.dbtower.mcp.internal.DiscordSignatureVerifier;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Discord 봇 인바운드 (심화 아크 5의 2단계 — 알림 → 진단 루프의 왕복 완성).
 *
 * 1단계(딥링크)가 "알림에서 콘솔로 건너오게" 했다면, 이 채널은 채팅에서 슬래시 커맨드
 * (/dbtower instance:<이름> question:<질문>)로 자연어 진단을 부르고 결과를 그 자리에서 받는다.
 * 채널 계층 원칙 그대로 — 여기엔 비즈니스 로직이 없고, 검증 후 DiagnosisService에 위임만 한다.
 *
 * 보안 3단계(로드맵 설계 그대로):
 * 1) Ed25519 요청 서명 — Discord가 모든 요청에 서명하며, 불일치는 401(프로토콜 요구사항이기도 하다).
 * 2) 채널 화이트리스트 — 명시된 채널 ID에서만 응답(기본 거부).
 * 3) 유저 화이트리스트 — 명시된 유저 ID만(기본 거부). SQL·진단 결과가 채팅방에 노출되는 채널이라
 *    마스킹(QueryMasker — DiagnosisService에 배선됨)과 함께 노출면을 명시적으로 좁힌다.
 * 공개키 미설정 = 기능 끔 — 404로 엔드포인트 존재 자체를 숨긴다(기능 게이트).
 *
 * 진단은 느리다(AI 다회 왕복) — Discord 3초 응답 제한을 지키려 DEFERRED(type 5)로 먼저 답하고,
 * 별도 스레드에서 진단 후 팔로업 웹훅(PATCH @original)으로 결과를 채운다.
 */
@RestController
public class DiscordInboundController {

    private static final Logger log = LoggerFactory.getLogger(DiscordInboundController.class);

    private static final int PING = 1;
    private static final int APPLICATION_COMMAND = 2;
    private static final int PONG_RESPONSE = 1;
    private static final int MESSAGE_RESPONSE = 4;
    private static final int DEFERRED_RESPONSE = 5;
    private static final int EPHEMERAL_FLAG = 64;
    /** Discord 메시지 본문 한도 — 초과분은 경계에서 자른다(문의 embed 절단과 같은 정직 규칙) */
    private static final int DISCORD_CONTENT_LIMIT = 2000;

    private final RegistryService registryService;
    private final DiagnosisService diagnosisService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dbtower-discord-inbound");
        t.setDaemon(true);
        return t;
    });

    private final String publicKeyHex;
    private final String applicationId;
    private final Set<String> channelAllowlist;
    private final Set<String> userAllowlist;

    public DiscordInboundController(RegistryService registryService, DiagnosisService diagnosisService,
                                    @Value("${dbtower.inbound.discord.public-key:}") String publicKeyHex,
                                    @Value("${dbtower.inbound.discord.application-id:}") String applicationId,
                                    @Value("${dbtower.inbound.discord.channel-allowlist:}") String channels,
                                    @Value("${dbtower.inbound.discord.user-allowlist:}") String users) {
        this.registryService = registryService;
        this.diagnosisService = diagnosisService;
        this.publicKeyHex = publicKeyHex == null ? "" : publicKeyHex.trim();
        this.applicationId = applicationId == null ? "" : applicationId.trim();
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
    void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @PostMapping("/api/inbound/discord")
    public ResponseEntity<String> interaction(
            @RequestHeader(value = "X-Signature-Ed25519", required = false) String signature,
            @RequestHeader(value = "X-Signature-Timestamp", required = false) String timestamp,
            @RequestBody byte[] body) throws Exception {
        if (publicKeyHex.isBlank()) {
            return ResponseEntity.notFound().build();   // 기능 게이트 — 미설정이면 존재 자체를 숨긴다
        }
        if (signature == null || timestamp == null
                || !DiscordSignatureVerifier.verify(publicKeyHex, timestamp, body, signature)) {
            return ResponseEntity.status(401).body("invalid request signature");
        }
        JsonNode req = mapper.readTree(body);
        int type = req.path("type").asInt();
        if (type == PING) {
            return json("{\"type\":%d}".formatted(PONG_RESPONSE));
        }
        if (type != APPLICATION_COMMAND) {
            return json("{\"type\":%d}".formatted(PONG_RESPONSE));
        }
        // 화이트리스트 — 기본 거부. 거부 사유는 요청자에게만 보이게(ephemeral).
        String channelId = req.path("channel_id").asText("");
        String userId = req.path("member").path("user").path("id")
                .asText(req.path("user").path("id").asText(""));
        if (!allowed(channelAllowlist, channelId) || !allowed(userAllowlist, userId)) {
            log.warn("Discord 인바운드 거부 — channel={} user={} (화이트리스트 밖)", channelId, userId);
            return ephemeral("이 채널/사용자에서는 DBTower 진단을 쓸 수 없습니다 — 관리자에게 화이트리스트 등록을 요청하세요.");
        }
        String instanceName = option(req, "instance");
        String question = option(req, "question");
        if (instanceName.isBlank() || question.isBlank()) {
            return ephemeral("instance와 question 옵션이 모두 필요합니다.");
        }
        DatabaseInstance instance = registryService.findAll().stream()
                .filter(i -> i.getName().equals(instanceName))
                .findFirst().orElse(null);
        if (instance == null) {
            return ephemeral("등록되지 않은 인스턴스: " + instanceName);
        }
        // 진단은 느리다 — 3초 제한 안에 DEFERRED로 답하고 팔로업으로 결과를 채운다
        String interactionToken = req.path("token").asText();
        worker.submit(() -> runDiagnosisAndFollowUp(instance, question, interactionToken));
        return json("{\"type\":%d}".formatted(DEFERRED_RESPONSE));
    }

    /** 화이트리스트 판정 — 빈 목록 = 전부 거부(기본 거부). 명시된 ID만 통과. */
    public static boolean allowed(Set<String> allowlist, String id) {
        return !allowlist.isEmpty() && allowlist.contains(id);
    }

    private static String option(JsonNode req, String name) {
        for (JsonNode opt : req.path("data").path("options")) {
            if (name.equals(opt.path("name").asText())) {
                return opt.path("value").asText("");
            }
        }
        return "";
    }

    private void runDiagnosisAndFollowUp(DatabaseInstance instance, String question, String token) {
        String content;
        try {
            var result = diagnosisService.diagnose(instance.getId(), instance.getType().name(),
                    instance.getName(), question);
            content = "[%s] %s".formatted(instance.getName(), result.answer());
        } catch (Exception e) {
            content = "진단 실패: " + e.getMessage();
        }
        if (content.length() > DISCORD_CONTENT_LIMIT) {
            content = content.substring(0, DISCORD_CONTENT_LIMIT - 20) + "\n...(한도 절단)";
        }
        try {
            String url = "https://discord.com/api/v10/webhooks/%s/%s/messages/@original"
                    .formatted(applicationId, token);
            HttpRequest patch = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(Map.of("content", content)),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(patch, HttpResponse.BodyHandlers.ofString());
            log.info("Discord 진단 팔로업 발송 — instance={} http={}", instance.getName(), res.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Discord 팔로업 발송 실패 — cause={}", e.getMessage());
        }
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().header("Content-Type", "application/json").body(body);
    }

    private ResponseEntity<String> ephemeral(String message) throws Exception {
        return json(mapper.writeValueAsString(Map.of(
                "type", MESSAGE_RESPONSE,
                "data", Map.of("content", message, "flags", EPHEMERAL_FLAG))));
    }
}
