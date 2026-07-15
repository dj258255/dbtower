package io.dbtower.registry.internal.web;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.HealthStatus;
import io.dbtower.registry.RegistryService;
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
            @NotBlank String password,
            // TLS 강제(선택) — 미지정(null)이면 false. 기존 IaC 페이로드 하위 호환
            Boolean useTls) {
        boolean tls() {
            return Boolean.TRUE.equals(useTls);
        }
    }

    /** 응답에 접속 정보(계정)는 노출하지 않는다 */
    public record InstanceResponse(Long id, String name, DbmsType type, String host, int port, String dbName,
                                   boolean useTls, boolean collectionEnabled) {
        static InstanceResponse from(DatabaseInstance i) {
            return new InstanceResponse(i.getId(), i.getName(), i.getType(), i.getHost(), i.getPort(), i.getDbName(),
                    i.isUseTls(), i.isCollectionEnabled());
        }
    }

    /** 수집 활성/격리 토글 (Phase F) — 문제 인스턴스를 삭제 없이 관제에서 잠시 뺀다. */
    public record CollectionToggle(@NotNull Boolean enabled) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstanceResponse register(@Valid @RequestBody RegisterRequest req) {
        DatabaseInstance saved = registryService.register(new DatabaseInstance(
                req.name(), req.type(), req.host(), req.port(), req.dbName(), req.username(), req.password(),
                req.tls()));
        return InstanceResponse.from(saved);
    }

    /**
     * 멱등 등록(upsert) — IaC 프로비저닝이 "생성하면 관제탑에 등록"을 재실행해도 안전하게.
     * 같은 이름이면 접속 정보를 갱신, 없으면 새로 등록. Ansible/K8s/Terraform이 이 엔드포인트를 쓴다.
     */
    @PutMapping
    public InstanceResponse upsert(@Valid @RequestBody RegisterRequest req) {
        DatabaseInstance saved = registryService.upsert(
                req.name(), req.type(), req.host(), req.port(), req.dbName(), req.username(), req.password(),
                req.tls());
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

    /** 수집 격리 토글 — enabled=false면 스냅샷 수집·운영 경보가 이 인스턴스를 건너뛴다(등록은 유지). */
    @PatchMapping("/{id}/collection")
    public InstanceResponse setCollection(@PathVariable Long id, @Valid @RequestBody CollectionToggle req) {
        return InstanceResponse.from(registryService.setCollectionEnabled(id, req.enabled()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        registryService.delete(id);
    }
}
