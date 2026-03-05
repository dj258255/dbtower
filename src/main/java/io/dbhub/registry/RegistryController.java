package io.dbhub.registry;

import io.dbhub.operator.HealthStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/instances")
public class RegistryController {

    private final RegistryService registryService;

    public RegistryController(RegistryService registryService) {
        this.registryService = registryService;
    }

    public record RegisterRequest(
            @NotBlank String name,
            @NotNull DbmsType type,
            // host/dbName은 JDBC URL에 들어가므로 패턴을 제한한다 —
            // "127.0.0.1?allowLoadLocalInfile=true" 같은 URL 파라미터 주입 방지
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9.\\-]+", message = "host는 호스트명/IP 형식만 허용합니다")
            String host,
            @Min(1) @Max(65535) int port,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]+", message = "dbName은 영문/숫자/밑줄만 허용합니다")
            String dbName,
            @NotBlank String username,
            @NotBlank String password) {
    }

    /** 응답에 접속 정보(계정)는 노출하지 않는다 */
    public record InstanceResponse(Long id, String name, DbmsType type, String host, int port, String dbName) {
        static InstanceResponse from(DatabaseInstance i) {
            return new InstanceResponse(i.getId(), i.getName(), i.getType(), i.getHost(), i.getPort(), i.getDbName());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstanceResponse register(@Valid @RequestBody RegisterRequest req) {
        DatabaseInstance saved = registryService.register(new DatabaseInstance(
                req.name(), req.type(), req.host(), req.port(), req.dbName(), req.username(), req.password()));
        return InstanceResponse.from(saved);
    }

    @GetMapping
    public List<InstanceResponse> list() {
        return registryService.findAll().stream().map(InstanceResponse::from).toList();
    }

    @GetMapping("/{id}/health")
    public HealthStatus health(@PathVariable Long id) {
        return registryService.health(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        registryService.delete(id);
    }
}
