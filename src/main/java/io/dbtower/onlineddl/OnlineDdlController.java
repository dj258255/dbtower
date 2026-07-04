package io.dbtower.onlineddl;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

/**
 * 온라인 스키마 변경 REST (B4). ADMIN만 호출 가능(SecurityConfig).
 * 기본은 dry-run(noop)이라 execute 없이 부르면 실제 테이블은 절대 바뀌지 않는다.
 */
@RestController
@RequestMapping("/api/instances/{id}")
public class OnlineDdlController {

    private final OnlineDdlService onlineDdlService;

    public OnlineDdlController(OnlineDdlService onlineDdlService) {
        this.onlineDdlService = onlineDdlService;
    }

    /** execute를 생략하면 false — dry-run. 실제 적용은 execute=true를 명시할 때만. */
    public record OnlineDdlRequest(@NotBlank String table, @NotBlank String alter, Boolean execute) {
    }

    public record OnlineDdlResponse(String status, String mode, String detail, String ghostTable) {
        static OnlineDdlResponse from(OnlineDdlResult r) {
            return new OnlineDdlResponse(r.status().name(), r.mode(), r.detail(), r.ghostTable());
        }
    }

    @PostMapping("/online-ddl")
    public OnlineDdlResponse run(@PathVariable Long id, @Valid @RequestBody OnlineDdlRequest req) {
        boolean execute = Boolean.TRUE.equals(req.execute());
        return OnlineDdlResponse.from(
                onlineDdlService.run(id, req.table(), req.alter(), execute));
    }
}
