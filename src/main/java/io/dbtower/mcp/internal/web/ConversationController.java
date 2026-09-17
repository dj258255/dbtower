package io.dbtower.mcp.internal.web;

import io.dbtower.mcp.internal.ConversationService;
import io.dbtower.mcp.internal.ConversationService.ConversationDetail;
import io.dbtower.mcp.internal.ConversationService.ConversationNotFound;
import io.dbtower.mcp.internal.ConversationService.ConversationView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 관제 AI 대화 REST (173절) — 대화는 브라우저가 아니라 서버가 보관한다. 화면을 닫아도 남고, 앞선 맥락의
 * 단일 권위가 여기다(브라우저가 보낸 history를 믿지 않는다).
 *
 * <p>인스턴스 범위·소유권 확인은 전부 서비스가 한다. 없는 인스턴스·팀 범위 밖 인스턴스는 registry가 404로,
 * 없거나 남의 것이거나 다른 인스턴스의 대화는 서비스가 같은 404로 만든다(존재 노출 방지).
 */
@RestController
@RequestMapping("/api/instances/{id}/conversations")
public class ConversationController {

    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    /** title은 없어도 된다 — 없으면 "새 대화". 검증(1~120자)은 서비스가 한다. */
    public record CreateBody(String title) {
    }

    public record RenameBody(String title) {
    }

    @GetMapping
    public List<ConversationView> list(@PathVariable Long id) {
        return conversations.list(id);
    }

    @PostMapping
    public ConversationView create(@PathVariable Long id, @RequestBody(required = false) CreateBody body) {
        return conversations.create(id, body == null ? null : body.title());
    }

    @GetMapping("/{conversationId}")
    public ConversationDetail detail(@PathVariable Long id, @PathVariable Long conversationId) {
        return conversations.detail(id, conversationId);
    }

    @PatchMapping("/{conversationId}")
    public ConversationView rename(@PathVariable Long id, @PathVariable Long conversationId,
                                   @RequestBody(required = false) RenameBody body) {
        return conversations.rename(id, conversationId, body == null ? null : body.title());
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @PathVariable Long conversationId) {
        conversations.delete(id, conversationId);
    }

    /** 남의 대화·다른 인스턴스의 대화도 여기로 온다 — 미등록과 구분되지 않아야 한다(존재 노출 방지). */
    @ExceptionHandler(ConversationNotFound.class)
    public ResponseEntity<Map<String, String>> notFound(ConversationNotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.getMessage()));
    }
}
