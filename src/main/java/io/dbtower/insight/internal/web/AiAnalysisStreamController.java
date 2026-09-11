package io.dbtower.insight.internal.web;

import io.dbtower.AiStreamExecutor;
import io.dbtower.insight.internal.AiAnalysisRunner;
import io.dbtower.insight.internal.web.InsightController.ExplainRequest;
import io.dbtower.operator.OperatorException;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 쿼리 상세 AI 분석 스트리밍(VERIFICATION 143절) — 결과는 {@code POST /ai-analysis}와 같고, 기다리는 동안 계획과 AI 글을 흘린다.
 * 이벤트: plan {plan, findings} -> text {delta}* -> result {plan, findings, aiAnalysis} 또는 error {status, message}.
 *
 * <p>범위 확인은 스트림을 열기 전에 요청 스레드에서 끝낸다(범위 밖이면 평범한 404). EXPLAIN과 AI는 작업 스레드에서 돌므로 그 실패는
 * HTTP 상태가 아니라 error 이벤트로 오고, 상태 번호는 한 번에 받는 경로의 GlobalExceptionHandler와 같게 맞춘다
 * (SELECT가 아니면 400, 대상 DB 실패는 502 — 원문 오류는 로그에만, 화면에는 errorId).
 */
@RestController
@RequestMapping("/api/instances/{id}")
public class AiAnalysisStreamController {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisStreamController.class);

    /** AI 한 턴의 상한(CLI 180초)과 EXPLAIN 제한보다 넉넉히 */
    private static final long STREAM_TIMEOUT_MS = 240_000;

    private final RegistryService registryService;
    private final AiAnalysisRunner runner;
    private final AiStreamExecutor streams;

    public AiAnalysisStreamController(RegistryService registryService, AiAnalysisRunner runner, AiStreamExecutor streams) {
        this.registryService = registryService;
        this.runner = runner;
        this.streams = streams;
    }

    @PostMapping("/ai-analysis/stream")
    public SseEmitter aiAnalysisStreaming(@PathVariable Long id, @RequestBody ExplainRequest req) {
        DatabaseInstance instance = registryService.findById(id);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        boolean started = streams.trySubmit(() -> {
            try {
                AiAnalysisRunner.Result r = runner.run(instance, req.sql(), new AiAnalysisRunner.Listener() {
                    @Override
                    public void plan(String plan, List<String> findings) {
                        send(emitter, "plan", Map.of("plan", plan, "findings", findings));
                    }

                    @Override
                    public void text(String delta) {
                        send(emitter, "text", Map.of("delta", delta));
                    }
                });
                // aiAnalysis는 null일 수 있다(AI 비활성) — Map.of는 null을 받지 않아 record로 보낸다
                send(emitter, "result", r);
            } catch (IllegalArgumentException e) {
                send(emitter, "error", Map.of("status", 400, "message", String.valueOf(e.getMessage())));
            } catch (OperatorException e) {
                String errorId = UUID.randomUUID().toString();
                log.warn("대상 DB 조회 실패(AI 분석 스트림) errorId={}", errorId, e);
                send(emitter, "error", Map.of("status", 502,
                        "message", "대상 데이터베이스 조회에 실패했습니다. 서버 로그를 확인하세요. errorId=" + errorId));
            } catch (RuntimeException e) {
                String errorId = UUID.randomUUID().toString();
                log.warn("AI 분석 스트림 실패 errorId={}", errorId, e);
                send(emitter, "error", Map.of("status", 500, "message", "분석 중 오류가 났습니다. errorId=" + errorId));
            } finally {
                emitter.complete();
            }
        });
        if (!started) {
            throw new StreamBusyException("AI 응답을 받는 자리가 모두 찼습니다. 잠시 뒤 다시 분석하세요");
        }
        return emitter;
    }

    static final class StreamBusyException extends RuntimeException {
        StreamBusyException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(StreamBusyException.class)
    public ResponseEntity<Map<String, String>> streamBusy(StreamBusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.getMessage()));
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 브라우저가 떠났다. 분석은 읽기뿐이라 끝까지 돌려도 대상에 남는 것이 없다
        }
    }
}
