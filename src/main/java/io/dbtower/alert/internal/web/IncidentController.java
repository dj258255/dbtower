package io.dbtower.alert.internal.web;

import io.dbtower.AiStreamExecutor;
import io.dbtower.alert.internal.AlertEmbeds;
import io.dbtower.alert.internal.IncidentReportService;
import io.dbtower.alert.internal.IncidentReportService.IncidentReport;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 인시던트 리포트 API (운영 병목 아크 B4). 장애 구간을 주면 리포트를 조립하고(IncidentReportService),
 * 요약 카드를 웹훅으로 보낸 뒤 전문(마크다운)을 돌려준다(콘솔 다운로드). 리포트에 설정 값·쿼리
 * 성능이 실려 파라미터 조회와 같은 운영 경계에 둔다(SecurityConfig).
 */
@RestController
public class IncidentController {

    private static final Logger log = LoggerFactory.getLogger(IncidentController.class);

    /** AI 요약 한 턴의 상한(CLI 180초)과 재료 조립보다 넉넉히 */
    private static final long STREAM_TIMEOUT_MS = 300_000;

    private final IncidentReportService reportService;
    private final RegistryService registryService;
    private final WebhookNotifier notifier;
    private final AiStreamExecutor streams;
    private final String baseUrl;

    public IncidentController(IncidentReportService reportService, RegistryService registryService,
                              WebhookNotifier notifier, AiStreamExecutor streams,
                              @Value("${dbtower.base-url:}") String baseUrl) {
        this.reportService = reportService;
        this.registryService = registryService;
        this.notifier = notifier;
        this.streams = streams;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    /**
     * 리포트 생성 + 요약 카드 발사. from/to는 ISO(예: 2026-07-18T03:00). publish=false면 카드 없이 전문만.
     */
    @PostMapping("/api/instances/{id}/incident-report")
    public IncidentReport generate(@PathVariable Long id,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                   @RequestParam(defaultValue = "true") boolean publish) {
        IncidentReport report = reportService.generate(id, from, to);
        publish(id, report, publish);
        return report;
    }

    /**
     * 흘려 받는 리포트(VERIFICATION 146절) — 결과와 카드 발사는 {@link #generate}와 같고, AI 요약을 뺀 리포트를 먼저 보이고 요약을 흘린다.
     * 이벤트: draft {markdown} -> text {delta}* -> result {IncidentReport} 또는 error {status, message}.
     * 범위 확인은 스트림을 열기 전 요청 스레드에서(범위 밖이면 404). 카드는 완성본으로만 보낸다 — 흘린 조각은 채널로 나가지 않는다.
     */
    @PostMapping("/api/instances/{id}/incident-report/stream")
    public SseEmitter generateStreaming(@PathVariable Long id,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                        @RequestParam(defaultValue = "true") boolean publish) {
        registryService.findById(id);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        boolean started = streams.trySubmit(() -> {
            try {
                IncidentReport report = reportService.generate(id, from, to, new IncidentReportService.Listener() {
                    @Override
                    public void draft(String markdownWithoutAi) {
                        send(emitter, "draft", Map.of("markdown", markdownWithoutAi));
                    }

                    @Override
                    public void text(String delta) {
                        send(emitter, "text", Map.of("delta", delta));
                    }
                });
                publish(id, report, publish);
                send(emitter, "result", report);
            } catch (IllegalArgumentException e) {
                send(emitter, "error", Map.of("status", 400, "message", String.valueOf(e.getMessage())));
            } catch (RuntimeException e) {
                String errorId = UUID.randomUUID().toString();
                log.warn("인시던트 리포트 스트림 실패 errorId={}", errorId, e);
                send(emitter, "error", Map.of("status", 500, "message", "리포트를 만들지 못했습니다. errorId=" + errorId));
            } finally {
                emitter.complete();
            }
        });
        if (!started) {
            throw new StreamBusyException("AI 응답을 받는 자리가 모두 찼습니다. 잠시 뒤 다시 생성하세요");
        }
        return emitter;
    }

    private void publish(Long id, IncidentReport report, boolean publish) {
        if (publish && notifier.isConfigured()) {
            DatabaseInstance instance = registryService.findById(id);
            String deeplink = baseUrl.isBlank() ? null : baseUrl + "/?instance=" + id + "&view=incident";
            String aiSummary = extractAiSummary(report.markdown());
            String fallback = "[DBTower 인시던트 리포트] " + report.instanceName()
                    + " " + report.from() + " ~ " + report.to();
            notifier.sendEmbed(fallback, id,
                    AlertEmbeds.forIncident(instance, report.from(), report.to(), aiSummary, deeplink));
        }
    }

    /** 마크다운에서 "## AI 요약" 절 본문만 뽑아 카드에 싣는다(없으면 null). */
    private static String extractAiSummary(String markdown) {
        int start = markdown.indexOf("## AI 요약");
        if (start < 0) {
            return null;
        }
        int bodyStart = markdown.indexOf('\n', start);
        int next = markdown.indexOf("\n## ", bodyStart);
        return markdown.substring(bodyStart + 1, next < 0 ? markdown.length() : next).strip();
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
            // 브라우저가 떠났다. 리포트는 끝까지 만들고 카드도 보낸다(publish=true면)
        }
    }
}
