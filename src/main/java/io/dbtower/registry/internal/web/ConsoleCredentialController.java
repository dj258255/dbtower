package io.dbtower.registry.internal.web;

import io.dbtower.registry.ConsoleCredentialService;
import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.CredentialSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 콘솔 계정 관리 — 전 경로 ADMIN(SecurityConfig). 응답에 비밀번호를 싣지 않는다. */
@RestController
@RequestMapping("/api/instances/{id}/credentials")
public class ConsoleCredentialController {

    private final ConsoleCredentialService credentials;

    public ConsoleCredentialController(ConsoleCredentialService credentials) {
        this.credentials = credentials;
    }

    public record CredentialRequest(@NotBlank String username, @NotBlank String password) {
    }

    @GetMapping
    public List<CredentialSummary> list(@PathVariable Long id) {
        return credentials.summaries(id);
    }

    @PutMapping("/{purpose}")
    public CredentialSummary save(@PathVariable Long id, @PathVariable CredentialPurpose purpose,
                                  @Valid @RequestBody CredentialRequest req) {
        return credentials.save(id, purpose, req.username(), req.password());
    }

    @DeleteMapping("/{purpose}")
    public Map<String, String> delete(@PathVariable Long id, @PathVariable CredentialPurpose purpose) {
        credentials.delete(id, purpose);
        return Map.of("deleted", purpose.name());
    }
}
