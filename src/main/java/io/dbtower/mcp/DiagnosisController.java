package io.dbtower.mcp;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final RegistryService registryService;
    private final DiagnosisService diagnosisService;

    public DiagnosisController(RegistryService registryService, DiagnosisService diagnosisService) {
        this.registryService = registryService;
        this.diagnosisService = diagnosisService;
    }

    public record DiagnoseRequest(@NotBlank String question) {
    }

    @PostMapping("/diagnose")
    public DiagnosisService.DiagnosisResult diagnose(@PathVariable Long id,
                                                     @RequestBody DiagnoseRequest req) {
        DatabaseInstance instance = registryService.findById(id); // 없는 인스턴스면 여기서 404
        return diagnosisService.diagnose(id, instance.getType().name(), instance.getName(), req.question());
    }
}
