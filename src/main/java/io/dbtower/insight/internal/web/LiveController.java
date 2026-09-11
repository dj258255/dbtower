package io.dbtower.insight.internal.web;

import io.dbtower.insight.internal.LiveSessionHub;
import io.dbtower.insight.internal.LiveSessionHub.CapacityExceededException;
import io.dbtower.insight.internal.LiveSessionHub.LiveFrame;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * 실시간 세션 스트림 (SSE) — 조회 권한은 {@code GET /sessions}와 같다(관제부터).
 *
 * <p>WebSocket이 아니라 SSE인 이유: 흐름이 서버에서 화면으로 한 방향뿐이고, 평범한 GET이라 기존 세션 쿠키·
 * 역할 인가·팀 스코프·프록시 설정을 그대로 탄다. 브라우저 EventSource가 끊기면 알아서 다시 붙는다.
 */
@RestController
@RequestMapping("/api/instances/{id}")
public class LiveController {

    private final RegistryService registryService;
    private final LiveSessionHub hub;
    private final long emitterTimeoutMs;

    public LiveController(RegistryService registryService, LiveSessionHub hub,
                          @Value("${dbtower.live.emitter-timeout-ms:300000}") long emitterTimeoutMs) {
        this.registryService = registryService;
        this.hub = hub;
        this.emitterTimeoutMs = emitterTimeoutMs;
    }

    @GetMapping(path = "/live/sessions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter liveSessions(@PathVariable Long id) {
        // 스코프 확인은 요청 스레드에서 끝낸다 — 틱 스레드에는 인증이 없다. 스코프 밖이면 여기서 404
        DatabaseInstance instance = registryService.findById(id);
        // 연결에 수명을 둔다. 끊기면 EventSource가 다시 붙고, 다시 붙는 요청이 인가를 새로 받는다
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        LiveSessionHub.Subscription subscription = hub.subscribe(instance.getId(), new LiveSessionHub.Subscriber() {
            @Override
            public void send(LiveFrame frame) throws IOException {
                emitter.send(SseEmitter.event().name("frame").id(Long.toString(frame.seq()))
                        .data(frame, MediaType.APPLICATION_JSON));
            }

            @Override
            public void end() {
                emitter.complete();
            }
        });
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(e -> subscription.close());
        return emitter;
    }

    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<Map<String, String>> capacity(CapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", e.getMessage()));
    }
}
