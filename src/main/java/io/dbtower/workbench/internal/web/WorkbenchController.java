package io.dbtower.workbench.internal.web;

import io.dbtower.workbench.StatementClassifier.Classification;
import io.dbtower.workbench.internal.ChangeExecutionService;
import io.dbtower.workbench.internal.ChangeExecutionService.ExecutionView;
import io.dbtower.workbench.internal.ChangeExecutionService.WorkloadView;
import io.dbtower.workbench.internal.WorkbenchAssistant;
import io.dbtower.workbench.internal.WorkbenchAssistant.AssistantRequest;
import io.dbtower.workbench.internal.WorkbenchAssistant.Reply;
import io.dbtower.workbench.internal.WorkbenchAssistant.ResultSample;
import io.dbtower.workbench.internal.WorkbenchAssistant.SettingView;
import io.dbtower.workbench.internal.WorkbenchService;
import io.dbtower.workbench.internal.WorkbenchService.CompareView;
import io.dbtower.workbench.internal.WorkbenchService.CsvExport;
import io.dbtower.workbench.internal.WorkbenchService.HistoryItem;
import io.dbtower.workbench.internal.WorkbenchService.InstanceView;
import io.dbtower.workbench.internal.WorkbenchService.QueryView;
import io.dbtower.workbench.internal.WorkbenchService.RuleView;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import io.dbtower.workbench.internal.WorksheetService;
import io.dbtower.workbench.internal.WorksheetService.TimelineItem;
import io.dbtower.workbench.internal.WorksheetService.VersionView;
import io.dbtower.workbench.internal.WorksheetService.WorksheetView;
import io.dbtower.workbench.internal.domain.MaskingStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 거버넌스 SQL 워크벤치 API. 마스킹 규칙 변경·인스턴스 설정 변경은 ADMIN(SecurityConfig), 나머지는 로그인 사용자 + 팀 범위.
 * 판정은 전부 서비스가 한다 — 여기서는 실행 성공 뒤 워크시트에 버전을 남기는 순서만 잇는다.
 */
@RestController
@RequestMapping("/api/workbench")
public class WorkbenchController {

    private final WorkbenchService workbench;
    private final WorksheetService worksheets;
    private final WorkbenchAssistant assistant;
    private final ChangeExecutionService changes;

    public WorkbenchController(WorkbenchService workbench, WorksheetService worksheets, WorkbenchAssistant assistant,
                               ChangeExecutionService changes) {
        this.workbench = workbench;
        this.worksheets = worksheets;
        this.assistant = assistant;
        this.changes = changes;
    }

    /**
     * 요청 본문의 불리언은 박싱 타입으로 받는다. Jackson 3는 원시 boolean에 값이 빠지면 null 대입을 거부해(FAIL_ON_NULL_FOR_PRIMITIVES)
     * `{}` 요청이 서비스에 닿기 전에 400이 됐다(라이브 검증에서 발견). 빠진 withoutCapture는 "캡처 포기 안 함"이다.
     */
    public record TicketRunBody(Boolean withoutCapture) {
        boolean skipCapture() {
            return Boolean.TRUE.equals(withoutCapture);
        }
    }

    /** dryRun은 빠지면 거부한다 — 빠진 값을 실제 되돌리기로 해석하지 않게 */
    public record RevertBody(@NotNull Boolean dryRun) {
    }

    /** applied도 빠지면 거부한다 — 확인 결과를 기본값으로 추측하지 않는다 */
    public record ResolveBody(@NotNull Boolean applied, @NotBlank @Size(max = 1_000) String note) {
    }

    public record CompareBody(@NotNull Long leftInstanceId, @NotNull Long rightInstanceId,
                              @NotBlank @Size(max = 100_000) String sql, List<String> keyColumns, Integer rowLimit) {
    }

    public record StatementRequest(@NotBlank @Size(max = 100_000) String sql, Integer rowLimit, Long worksheetId) {
    }

    public record ExportRequest(@NotBlank @Size(max = 100_000) String sql, @NotBlank @Size(max = 500) String reason) {
    }

    public record RuleRequest(Long instanceId, @NotBlank String columnPattern, @NotNull MaskingStrategy strategy,
                              @Size(max = 200) String note) {
    }

    public record WorksheetCreate(@Size(max = 100) String title) {
    }

    public record WorksheetPatch(@Size(max = 100) String title, @Size(max = 100_000) String currentSql) {
    }

    public record AssistantBody(@NotBlank @Size(max = 4_000) String message, List<String> tables, List<String> columns,
                                @Size(max = 100_000) String failedSql, @Size(max = 4_000) String failedError,
                                ResultSample result) {
    }

    public record SettingBody(boolean allowAiResultValues) {
    }

    public record QueryResponse(QueryView result, VersionView version) {
    }

    @GetMapping("/instances")
    public List<InstanceView> instances() {
        return workbench.instances();
    }

    @PostMapping("/instances/{id}/classify")
    public Classification classify(@PathVariable Long id, @Valid @RequestBody StatementRequest req) {
        return workbench.classify(id, req.sql());
    }

    @PostMapping("/instances/{id}/query")
    public QueryResponse query(@PathVariable Long id, @Valid @RequestBody StatementRequest req) {
        QueryView view = workbench.run(id, req.sql(), req.rowLimit());
        VersionView version = req.worksheetId() == null ? null
                : worksheets.recordRun(req.worksheetId(), id, req.sql()).orElse(null);
        return new QueryResponse(view, version);
    }

    @PostMapping("/instances/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id, @Valid @RequestBody ExportRequest req) {
        CsvExport csv = workbench.export(id, req.sql(), req.reason());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(csv.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Row-Count", String.valueOf(csv.rowCount()))
                .header("X-Truncated", String.valueOf(csv.truncated()))
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.body().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/instances/{id}/history")
    public List<HistoryItem> history(@PathVariable Long id, @RequestParam(defaultValue = "30") int limit) {
        return workbench.history(id, limit);
    }

    // ---------- 워크시트 ----------

    @GetMapping("/instances/{id}/worksheets")
    public List<WorksheetView> worksheets(@PathVariable Long id) {
        return worksheets.list(id);
    }

    @PostMapping("/instances/{id}/worksheets")
    public WorksheetView createWorksheet(@PathVariable Long id, @Valid @RequestBody WorksheetCreate req) {
        return worksheets.create(id, req.title());
    }

    @PatchMapping("/worksheets/{worksheetId}")
    public WorksheetView updateWorksheet(@PathVariable Long worksheetId, @Valid @RequestBody WorksheetPatch req) {
        return worksheets.update(worksheetId, req.title(), req.currentSql());
    }

    @DeleteMapping("/worksheets/{worksheetId}")
    public Map<String, Object> archiveWorksheet(@PathVariable Long worksheetId) {
        worksheets.archive(worksheetId);
        return Map.of("archived", worksheetId);
    }

    @GetMapping("/worksheets/{worksheetId}/timeline")
    public List<TimelineItem> timeline(@PathVariable Long worksheetId) {
        return worksheets.timeline(worksheetId);
    }

    @PostMapping("/worksheets/{worksheetId}/versions/{versionNo}/restore")
    public VersionView restore(@PathVariable Long worksheetId, @PathVariable int versionNo) {
        return worksheets.restore(worksheetId, versionNo);
    }

    @PostMapping("/worksheets/{worksheetId}/assistant")
    public Reply ask(@PathVariable Long worksheetId, @Valid @RequestBody AssistantBody req) {
        return assistant.ask(worksheetId, new AssistantRequest(req.message(), req.tables(), req.columns(),
                req.failedSql(), req.failedError(), req.result()));
    }

    // ---------- 승인 티켓 실행·전후 비교 (드라이런·실행·되돌리기는 ADMIN, SecurityConfig) ----------

    @PostMapping("/tickets/{reviewId}/dry-run")
    public ExecutionView dryRun(@PathVariable Long reviewId, @RequestBody(required = false) TicketRunBody req) {
        return changes.dryRun(reviewId, req != null && req.skipCapture());
    }

    @PostMapping("/tickets/{reviewId}/execute")
    public ExecutionView execute(@PathVariable Long reviewId, @RequestBody(required = false) TicketRunBody req) {
        return changes.execute(reviewId, req != null && req.skipCapture());
    }

    /** 본문을 필수로 받는다 — 본문이 빠진 요청이 드라이런이 아니라 실제 되돌리기로 해석되지 않게 */
    @PostMapping("/tickets/{reviewId}/revert")
    public ExecutionView revert(@PathVariable Long reviewId, @Valid @RequestBody RevertBody req) {
        return changes.revert(reviewId, req.dryRun());
    }

    @PostMapping("/tickets/{reviewId}/resolve")
    public ExecutionView resolve(@PathVariable Long reviewId, @Valid @RequestBody ResolveBody req) {
        return changes.resolve(reviewId, req.applied(), req.note());
    }

    @GetMapping("/tickets/{reviewId}/executions")
    public List<ExecutionView> executions(@PathVariable Long reviewId) {
        return changes.executions(reviewId);
    }

    @GetMapping("/executions/{executionId}/workload")
    public WorkloadView workload(@PathVariable Long executionId, @RequestParam(defaultValue = "60") int windowMinutes) {
        return changes.workload(executionId, windowMinutes);
    }

    @PostMapping("/compare")
    public CompareView compare(@Valid @RequestBody CompareBody req) {
        return workbench.compare(req.leftInstanceId(), req.rightInstanceId(), req.sql(), req.keyColumns(), req.rowLimit());
    }

    // ---------- 인스턴스 설정·마스킹 규칙 ----------

    @GetMapping("/instances/{id}/settings")
    public SettingView setting(@PathVariable Long id) {
        return assistant.setting(id);
    }

    @PutMapping("/instances/{id}/settings")
    public SettingView updateSetting(@PathVariable Long id, @RequestBody SettingBody req) {
        return assistant.updateSetting(id, req.allowAiResultValues());
    }

    @GetMapping("/masking-rules")
    public List<RuleView> maskingRules(@RequestParam(required = false) Long instanceId) {
        return workbench.maskingRules(instanceId);
    }

    @PostMapping("/masking-rules")
    public RuleView addMaskingRule(@Valid @RequestBody RuleRequest req) {
        return workbench.addMaskingRule(req.instanceId(), req.columnPattern(), req.strategy(), req.note());
    }

    @DeleteMapping("/masking-rules/{ruleId}")
    public Map<String, Object> deleteMaskingRule(@PathVariable Long ruleId) {
        workbench.deleteMaskingRule(ruleId);
        return Map.of("deleted", ruleId);
    }

    @ExceptionHandler(WorkbenchRejection.class)
    public ResponseEntity<Map<String, Object>> rejected(WorkbenchRejection e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("classification", e.classification());
        return ResponseEntity.status(e.status()).body(body);
    }
}
