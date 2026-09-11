package io.dbtower.mcp.internal.web;

import io.dbtower.AiStreamExecutor;
import io.dbtower.mcp.internal.DiagnosisService;
import io.dbtower.mcp.internal.DiagnosisService.DiagnosisListener;
import io.dbtower.mcp.internal.DiagnosisService.ToolCallTrace;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import jakarta.validation.constraints.NotBlank;
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
import java.util.Map;

/**
 * 자연어 근본원인 진단 REST (Phase D3).
 *
 * POST /api/instances/{id}/diagnose {question} — AI가 MCP 읽기 도구를 스스로 연쇄 호출해
 * 근본원인을 서술한다. 응답에 최종 답변뿐 아니라 "어떤 도구를 왜 불렀나"(toolCalls)를 함께 실어
 * 투명성을 준다. explain·ai-analysis와 같은 진단 카테고리라 인증 사용자(VIEWER)면 되고,
 * 대상 DB를 변경하지 않는다(읽기 전용 도구만 AI에 노출 — DiagnosisService 참고).
 */
@RestController
@RequestMapping("/api/instances/{id}")
public class DiagnosisController {

    /** 스텝 5회 + 최종 종합 1회가 각각 수십 초까지 걸린다. 연결이 먼저 끊기면 진단은 끝나는데 화면만 실패로 보인다 */
    private static final long STREAM_TIMEOUT_MS = 600_000;

    private final RegistryService registryService;
    private final DiagnosisService diagnosisService;
    private final AiStreamExecutor streams;

    public DiagnosisController(RegistryService registryService, DiagnosisService diagnosisService,
                               AiStreamExecutor streams) {
        this.registryService = registryService;
        this.diagnosisService = diagnosisService;
        this.streams = streams;
    }

    public record DiagnoseRequest(@NotBlank String question) {
    }

    @PostMapping("/diagnose")
    public DiagnosisService.DiagnosisResult diagnose(@PathVariable Long id,
                                                     @RequestBody DiagnoseRequest req) {
        DatabaseInstance instance = registryService.findById(id); // 없는 인스턴스면 여기서 404
        return diagnosisService.diagnose(id, instance.getType().name(), instance.getName(), req.question());
    }

    /**
     * 진단 스트리밍(VERIFICATION 141절) — 결과는 {@link #diagnose}와 같고, 스텝마다 진행을 흘린다.
     * 이벤트: thinking {step, synthesis} → tool {ToolCallTrace}* → … → result {DiagnosisResult} 또는 error {message}.
     *
     * <p>범위 확인은 스트림을 열기 전에 요청 스레드에서 끝낸다(범위 밖이면 평범한 404). 작업 스레드에는 요청 주체의
     * SecurityContext가 실려 가므로 진단 안의 범위 고정·감사 주체도 동기 경로와 같다(AiStreamExecutor).
     */
    @PostMapping("/diagnose/stream")
    public SseEmitter diagnoseStreaming(@PathVariable Long id, @RequestBody DiagnoseRequest req) {
        DatabaseInstance instance = registryService.findById(id);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        boolean started = streams.trySubmit(() -> {
            try {
                DiagnosisService.DiagnosisResult result = diagnosisService.diagnose(id, instance.getType().name(),
                        instance.getName(), req.question(), new DiagnosisListener() {
                            @Override
                            public void thinking(int step, boolean synthesis) {
                                send(emitter, "thinking", Map.of("step", step, "synthesis", synthesis));
                            }

                            @Override
                            public void toolCall(ToolCallTrace trace) {
                                send(emitter, "tool", trace);
                            }
                        });
                send(emitter, "result", result);
            } catch (RuntimeException e) {
                send(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
            } finally {
                emitter.complete();
            }
        });
        if (!started) {
            throw new StreamBusyException("AI 응답을 받는 자리가 모두 찼습니다. 잠시 뒤 다시 진단하세요");
        }
        return emitter;
    }

    /** 스트림 자리가 없을 때 — 기본 오류 본문은 사유 문장을 싣지 않아(include-message=never) 화면이 이유를 말할 수 없다 */
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
            // 브라우저가 떠났다. 진단은 멈추지 않는다(감사 기록이 이미 남았고, 도구 호출은 읽기뿐이다)
        }
    }
}
