package io.dbtower.alert.internal.web;

import io.dbtower.alert.internal.AlertEmbeds;
import io.dbtower.alert.internal.IncidentReportService;
import io.dbtower.alert.internal.IncidentReportService.IncidentReport;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 인시던트 리포트 API (운영 병목 아크 B4). 장애 구간을 주면 리포트를 조립하고(IncidentReportService),
 * 요약 카드를 웹훅으로 보낸 뒤 전문(마크다운)을 돌려준다(콘솔 다운로드). 리포트에 설정 값·쿼리
 * 성능이 실려 파라미터 조회와 같은 ADMIN 경계에 둔다(SecurityConfig).
 */
@RestController
public class IncidentController {

    private final IncidentReportService reportService;
    private final RegistryService registryService;
    private final WebhookNotifier notifier;
    private final String baseUrl;

    public IncidentController(IncidentReportService reportService, RegistryService registryService,
                             WebhookNotifier notifier, @Value("${dbtower.base-url:}") String baseUrl) {
        this.reportService = reportService;
        this.registryService = registryService;
        this.notifier = notifier;
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
        if (publish && notifier.isConfigured()) {
            DatabaseInstance instance = registryService.findById(id);
            String deeplink = baseUrl.isBlank() ? null : baseUrl + "/?instance=" + id + "&view=incident";
            String aiSummary = extractAiSummary(report.markdown());
            String fallback = "[DBTower 인시던트 리포트] " + report.instanceName()
                    + " " + report.from() + " ~ " + report.to();
            notifier.sendEmbed(fallback, id,
                    AlertEmbeds.forIncident(instance, report.from(), report.to(), aiSummary, deeplink));
        }
        return report;
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
}
