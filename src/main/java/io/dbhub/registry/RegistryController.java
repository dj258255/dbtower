package io.dbhub.registry;

import io.dbhub.operator.HealthStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
            @NotBlank String host,
            @Min(1) @Max(65535) int port,
            @NotBlank String dbName,
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
    public InstanceResponse register(@RequestBody RegisterRequest req) {
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
