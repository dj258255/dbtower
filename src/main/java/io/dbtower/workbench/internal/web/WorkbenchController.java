package io.dbtower.workbench.internal.web;

import io.dbtower.workbench.StatementClassifier.Classification;
import io.dbtower.workbench.internal.WorkbenchService;
import io.dbtower.workbench.internal.WorkbenchService.CsvExport;
import io.dbtower.workbench.internal.WorkbenchService.HistoryItem;
import io.dbtower.workbench.internal.WorkbenchService.InstanceView;
import io.dbtower.workbench.internal.WorkbenchService.QueryView;
import io.dbtower.workbench.internal.WorkbenchService.RuleView;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 거버넌스 SQL 워크벤치 API. 마스킹 규칙 변경은 ADMIN(SecurityConfig), 나머지는 로그인 사용자 + 팀 범위. */
@RestController
@RequestMapping("/api/workbench")
public class WorkbenchController {

    private final WorkbenchService workbench;

    public WorkbenchController(WorkbenchService workbench) {
        this.workbench = workbench;
    }

    public record StatementRequest(@NotBlank @Size(max = 100_000) String sql, Integer rowLimit) {
    }

    public record ExportRequest(@NotBlank @Size(max = 100_000) String sql, @NotBlank @Size(max = 500) String reason) {
    }

    public record RuleRequest(Long instanceId, @NotBlank String columnPattern, @NotNull MaskingStrategy strategy,
                              @Size(max = 200) String note) {
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
    public QueryView query(@PathVariable Long id, @Valid @RequestBody StatementRequest req) {
        return workbench.run(id, req.sql(), req.rowLimit());
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
