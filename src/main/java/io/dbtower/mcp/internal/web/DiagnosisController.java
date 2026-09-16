package io.dbtower.mcp.internal.web;

import io.dbtower.AiStreamExecutor;
import io.dbtower.mcp.internal.ConversationService;
import io.dbtower.mcp.internal.ConversationService.ConversationNotFound;
import io.dbtower.mcp.internal.DiagnosisService;
import io.dbtower.mcp.internal.DiagnosisService.DiagnosisListener;
import io.dbtower.mcp.internal.DiagnosisService.DiagnosisResult;
import io.dbtower.mcp.internal.DiagnosisService.PriorTurn;
import io.dbtower.mcp.internal.DiagnosisService.ToolCallTrace;
import io.dbtower.operator.OperatorException;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import jakarta.validation.constraints.NotBlank;
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

    private static final Logger log = LoggerFactory.getLogger(DiagnosisController.class);

    /** 스텝 5회 + 최종 종합 1회가 각각 수십 초까지 걸린다. 연결이 먼저 끊기면 진단은 끝나는데 화면만 실패로 보인다 */
    private static final long STREAM_TIMEOUT_MS = 600_000;

    private final RegistryService registryService;
    private final DiagnosisService diagnosisService;
    private final ConversationService conversations;
    private final AiStreamExecutor streams;

    public DiagnosisController(RegistryService registryService, DiagnosisService diagnosisService,
                               ConversationService conversations, AiStreamExecutor streams) {
        this.registryService = registryService;
        this.diagnosisService = diagnosisService;
        this.conversations = conversations;
        this.streams = streams;
    }

    /**
     * 대화에 이어서 진단할 때는 그 대화의 id를 보낸다. 앞선 맥락은 <b>서버가 DB에서</b> 읽는다 —
     * 예전에는 브라우저가 보낸 history(질문·답 쌍)를 그대로 실었는데, 위조할 수 있는 입력이 프롬프트에 들어갔다.
     * conversationId가 없으면 지금처럼 한 번짜리 진단이다(맥락 없음, 저장 없음) — MCP·API 호출자 호환.
     */
    public record DiagnoseRequest(@NotBlank String question, Long conversationId) {
    }

    @PostMapping("/diagnose")
    public DiagnosisService.DiagnosisResult diagnose(@PathVariable Long id,
                                                     @RequestBody DiagnoseRequest req) {
        DatabaseInstance instance = registryService.findById(id); // 없는 인스턴스면 여기서 404
        List<PriorTurn> history = historyOf(id, req.conversationId()); // 없거나 남의 대화면 여기서 404
        long startedAt = System.currentTimeMillis();
        DiagnosisResult result = diagnosisService.diagnose(id, instance.getType().name(), instance.getName(),
                req.question(), history, DiagnosisService.DiagnosisListener.NONE);
        record(id, req, result, System.currentTimeMillis() - startedAt);
        return result;
    }

    /**
     * 진단 스트리밍(VERIFICATION 141절) — 결과는 {@link #diagnose}와 같고, 스텝마다 진행을 흘린다.
     * 이벤트: thinking {step, synthesis} → tool {ToolCallTrace}* → … → result {DiagnosisResult} 또는 error {message}.
     *
     * <p>범위·소유 확인은 스트림을 열기 전에 요청 스레드에서 끝낸다(범위 밖이면 평범한 404). 작업 스레드에는 요청 주체의
     * SecurityContext가 실려 가므로 진단 안의 범위 고정·감사 주체도 동기 경로와 같다(AiStreamExecutor).
     */
    @PostMapping("/diagnose/stream")
    public SseEmitter diagnoseStreaming(@PathVariable Long id, @RequestBody DiagnoseRequest req) {
        DatabaseInstance instance = registryService.findById(id);
        List<PriorTurn> history = historyOf(id, req.conversationId());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        boolean started = streams.trySubmit(() -> {
            try {
                long startedAt = System.currentTimeMillis();
                DiagnosisService.DiagnosisResult result = diagnosisService.diagnose(id, instance.getType().name(),
                        instance.getName(), req.question(), history, new DiagnosisListener() {
                            @Override
                            public void thinking(int step, boolean synthesis) {
                                send(emitter, "thinking", Map.of("step", step, "synthesis", synthesis));
                            }

                            @Override
                            public void toolCall(ToolCallTrace trace) {
                                send(emitter, "tool", trace);
                            }
                        });
                long tookMs = System.currentTimeMillis() - startedAt;
                send(emitter, "result", result);
                // 흘린 조각은 저장하지 않는다 — 답이 완성된 뒤에만, 그리고 AI가 실제로 답했을 때만 한 턴 남긴다
                recordQuietly(id, req, result, tookMs);
            } catch (IllegalArgumentException e) {
                send(emitter, "error", Map.of("status", 400, "message", String.valueOf(e.getMessage())));
            } catch (IllegalStateException e) {
                send(emitter, "error", Map.of("status", 409, "message", String.valueOf(e.getMessage())));
            } catch (OperatorException e) {
                // 대상 DB 원문 오류에는 호스트·스키마·드라이버 문장이 섞인다(CWE-209) — 한 번에 받는 경로(GlobalExceptionHandler)처럼
                // 원문은 서버 로그에만 남기고 화면에는 errorId만. 전에는 e.getMessage()를 그대로 흘렸다(148절 감사)
                String errorId = UUID.randomUUID().toString();
                log.warn("진단 스트림 대상 조회 실패 errorId={}", errorId, e);
                send(emitter, "error", Map.of("status", 502, "message", "대상 데이터베이스 조회에 실패했습니다. errorId=" + errorId));
            } catch (RuntimeException e) {
                String errorId = UUID.randomUUID().toString();
                log.warn("진단 스트림 실패 errorId={}", errorId, e);
                send(emitter, "error", Map.of("status", 500, "message", "진단을 끝내지 못했습니다. errorId=" + errorId));
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

    /** 없거나 남의 것이거나 다른 인스턴스의 대화 — 미등록과 같은 404로 답한다(존재 노출 방지). */
    @ExceptionHandler(ConversationNotFound.class)
    public ResponseEntity<Map<String, String>> conversationNotFound(ConversationNotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.getMessage()));
    }

    /** 대화를 이어가는 진단이면 서버 기록에서 앞선 턴을 읽는다 — 브라우저가 보낸 맥락은 쓰지 않는다. */
    private List<PriorTurn> historyOf(Long instanceId, Long conversationId) {
        return conversationId == null ? List.of() : conversations.history(instanceId, conversationId);
    }

    /**
     * 완성된 턴 하나를 남긴다. AI가 답했을 때(aiEnabled)만이고, 오류·비활성이면 남기지 않는다.
     * 대화 없이 부른 한 번짜리 진단(conversationId 없음)은 저장하지 않는다 — MCP·API 호출자 호환 경로다.
     *
     * <p>답이 비어 있으면 저장하지 않는 이유: AI 백엔드가 실패하면 DiagnosisService는 {@code aiEnabled=true}인데
     * {@code answer=null}인 결과를 돌려준다(사유는 note에만 있고 note는 저장하지 않는다). aiEnabled만 보면
     * 답 없는 턴이 대화에 남고, 다음 진단의 맥락에 "앞 질문"으로 사실처럼 실린다 — 이 묶음이 없애려던 바로 그 모양이다.
     */
    private void record(Long instanceId, DiagnoseRequest req, DiagnosisResult result, long tookMs) {
        if (req.conversationId() == null || !result.aiEnabled()
                || result.answer() == null || result.answer().isBlank()) {
            return;
        }
        conversations.appendTurn(instanceId, req.conversationId(), req.question(), result, tookMs);
    }

    /**
     * 스트림 경로의 턴 저장 — 실패해도 이벤트를 보내지 않는다. 답은 이미 result 이벤트로 화면에 갔고, 그 뒤에
     * error 이벤트를 보내면 "답을 받고 나서 실패를 보는" 화면이 된다(스트리밍 도중 대화가 지워진 경우가 그렇다).
     * 사유는 서버 로그에 errorId로만 남긴다.
     *
     * <p>동기 경로({@code /diagnose})는 반대로 저장 실패를 그대로 올린다 — 응답 하나로 성패를 알아야 하는 호출자에게
     * "턴이 안 남았다"는 사실은 조용히 넘길 일이 아니다. 이 비대칭은 의도다.
     */
    private void recordQuietly(Long instanceId, DiagnoseRequest req, DiagnosisResult result, long tookMs) {
        try {
            record(instanceId, req, result, tookMs);
        } catch (RuntimeException e) {
            String errorId = UUID.randomUUID().toString();
            log.warn("진단 턴 저장 실패 errorId={} conversationId={}", errorId, req.conversationId(), e);
        }
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 브라우저가 떠났다. 진단은 멈추지 않는다(감사 기록이 이미 남았고, 도구 호출은 읽기뿐이다)
        }
    }
}
