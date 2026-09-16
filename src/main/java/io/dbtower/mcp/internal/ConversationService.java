package io.dbtower.mcp.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.mcp.internal.DiagnosisService.DiagnosisResult;
import io.dbtower.mcp.internal.DiagnosisService.PriorTurn;
import io.dbtower.mcp.internal.DiagnosisService.ToolCallTrace;
import io.dbtower.mcp.internal.domain.Conversation;
import io.dbtower.mcp.internal.domain.ConversationTurn;
import io.dbtower.mcp.internal.persistence.ConversationRepository;
import io.dbtower.mcp.internal.persistence.ConversationTurnRepository;
import io.dbtower.registry.RegistryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관제 AI 대화의 서버 보관소 — 대화 목록·제목·턴을 소유자별로 다루고, 진단이 쓸 앞선 맥락을 여기서만 만든다.
 *
 * <p>대화는 만든 사람의 것이다(다른 사용자·다른 인스턴스의 대화는 미등록과 같은 404). 팀 범위가 바뀌면 자기 대화라도
 * 그 인스턴스를 더는 볼 수 없으니 매 진입에서 {@link RegistryService#findById}를 다시 통과시킨다(WorksheetService와 같은 규칙).
 *
 * <p>브라우저가 보낸 맥락은 쓰지 않는다 — 진단이 참고하는 앞선 대화는 이 서비스가 DB에서 읽은 턴뿐이다.
 */
@Service
public class ConversationService {

    static final String DEFAULT_TITLE = "새 대화";
    /** 목록 한 번에 돌려주는 대화 수 — 화면이 한 화면에 보여주는 양보다 넉넉하고, 넘으면 화면이 더 좁혀 묻는다. */
    private static final int LIST_LIMIT = 50;
    private static final int TITLE_MAX = 120;
    /** 첫 턴의 제목 길이. 목록 한 줄에 들어가는 만큼만 — 나머지는 대화를 열어 보면 된다. */
    private static final int FIRST_TITLE_CHARS = 40;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RegistryService registry;
    private final ConversationRepository conversations;
    private final ConversationTurnRepository turns;

    public ConversationService(RegistryService registry, ConversationRepository conversations,
                               ConversationTurnRepository turns) {
        this.registry = registry;
        this.conversations = conversations;
        this.turns = turns;
    }

    /** 대화가 없거나 남의 것이거나 다른 인스턴스의 것이면 404 — 존재를 알리지 않는다. */
    public static final class ConversationNotFound extends RuntimeException {
        public ConversationNotFound(Long conversationId) {
            super("대화를 찾을 수 없습니다: " + conversationId);
        }
    }

    public record ConversationView(Long id, String title, LocalDateTime updatedAt, long turnCount) {
    }

    public record TurnView(String question, String answer, String rootCause, String confidence, String backend,
                           List<ToolCallTrace> toolCalls, long tookMs, LocalDateTime createdAt) {
    }

    public record ConversationDetail(Long id, String title, LocalDateTime updatedAt, List<TurnView> turns) {
    }

    @Transactional(readOnly = true)
    public List<ConversationView> list(Long instanceId) {
        registry.findById(instanceId);
        return conversations
                .findByPrincipalAndInstanceIdOrderByUpdatedAtDesc(principal(), instanceId, PageRequest.of(0, LIST_LIMIT))
                .stream().map(this::view).toList();
    }

    @Transactional
    public ConversationView create(Long instanceId, String title) {
        registry.findById(instanceId);
        String name = title == null || title.isBlank() ? DEFAULT_TITLE : cleanTitle(title);
        return view(conversations.save(new Conversation(principal(), instanceId, name)));
    }

    @Transactional(readOnly = true)
    public ConversationDetail detail(Long instanceId, Long conversationId) {
        Conversation conversation = requireOwned(instanceId, conversationId);
        List<TurnView> history = turns.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream()
                .map(this::view).toList();
        return new ConversationDetail(conversation.getId(), conversation.getTitle(), conversation.getUpdatedAt(),
                history);
    }

    @Transactional
    public ConversationView rename(Long instanceId, Long conversationId, String title) {
        Conversation conversation = requireOwned(instanceId, conversationId);
        conversation.rename(cleanTitle(title));
        return view(conversations.save(conversation));
    }

    @Transactional
    public void delete(Long instanceId, Long conversationId) {
        Conversation conversation = requireOwned(instanceId, conversationId);
        turns.deleteByConversationId(conversation.getId());
        conversations.delete(conversation);
    }

    /**
     * 진단이 참고할 앞선 대화 — 오래된 순 그대로 넘긴다. 최근 몇 턴을 어떻게 실을지는 프롬프트 쪽
     * ({@link DiagnosisService#historyBlock})이 정한다.
     */
    @Transactional(readOnly = true)
    public List<PriorTurn> history(Long instanceId, Long conversationId) {
        Conversation conversation = requireOwned(instanceId, conversationId);
        return turns.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId()).stream()
                .map(t -> new PriorTurn(t.getQuestion(), t.getAnswer()))
                .toList();
    }

    /**
     * 진단이 끝난 턴 하나를 남긴다. 스트리밍 조각은 저장하지 않는다 — 완성된 답만 들어온다(호출자가 보장).
     * 첫 턴이면 제목을 질문 앞 40자로 바꾼다("새 대화"인 동안에만).
     */
    @Transactional
    public void appendTurn(Long instanceId, Long conversationId, String question, DiagnosisResult result, long tookMs) {
        Conversation conversation = requireOwned(instanceId, conversationId);
        turns.save(new ConversationTurn(conversation.getId(), question, result.answer(), result.rootCause(),
                result.confidence(), result.backend(), writeToolCalls(result.toolCalls()), tookMs));
        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversation.rename(deriveTitle(question));
        }
        conversation.touch();
        conversations.save(conversation);
    }

    private Conversation requireOwned(Long instanceId, Long conversationId) {
        registry.findById(instanceId);
        return conversations.findById(conversationId)
                .filter(c -> c.getPrincipal().equals(principal()) && c.getInstanceId().equals(instanceId))
                .orElseThrow(() -> new ConversationNotFound(conversationId));
    }

    private ConversationView view(Conversation conversation) {
        return new ConversationView(conversation.getId(), conversation.getTitle(), conversation.getUpdatedAt(),
                turns.countByConversationId(conversation.getId()));
    }

    private TurnView view(ConversationTurn turn) {
        return new TurnView(turn.getQuestion(), turn.getAnswer(), turn.getRootCause(), turn.getConfidence(),
                turn.getBackend(), readToolCalls(turn.getToolCalls()), turn.getTookMs(), turn.getCreatedAt());
    }

    /**
     * 도구 호출 기록에서 결과 본문(resultSnippet)만 비운 사본을 남긴다. 대상 DB에서 온 값(쿼리·세션 정보)을
     * 메타 DB에 쌓으면 진단용 보관소가 값 저장소가 된다 — 이름·마스킹된 인자·부른 이유·거부 여부면
     * "AI가 무엇을 근거로 삼았나"는 화면에 보여줄 만큼 남는다.
     */
    private String writeToolCalls(List<ToolCallTrace> toolCalls) {
        List<ToolCallTrace> stripped = toolCalls.stream()
                .map(t -> new ToolCallTrace(t.step(), t.tool(), t.arguments(), t.reason(), "", t.rejected()))
                .toList();
        try {
            return mapper.writeValueAsString(stripped);
        } catch (Exception e) {
            throw new IllegalStateException("도구 호출 기록을 직렬화하지 못했습니다", e);
        }
    }

    private List<ToolCallTrace> readToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("저장된 도구 호출 기록을 읽지 못했습니다", e);
        }
    }

    /** 제목은 공백을 걷어낸 1~120자만 받는다 — 목록 한 줄이 제목 길이에 끌려다니지 않게. */
    private static String cleanTitle(String title) {
        String t = title == null ? "" : title.strip();
        if (t.isEmpty() || t.length() > TITLE_MAX) {
            throw new IllegalArgumentException("제목은 1~" + TITLE_MAX + "자여야 합니다");
        }
        return t;
    }

    /** 질문 앞 40자. 줄바꿈은 공백으로 접는다 — 목록의 제목 한 줄이 여러 줄이 되면 표가 깨진다. */
    static String deriveTitle(String question) {
        String one = question == null ? "" : question.replaceAll("\\s+", " ").strip();
        if (one.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return one.length() > FIRST_TITLE_CHARS ? one.substring(0, FIRST_TITLE_CHARS) : one;
    }

    /** 요청 주체의 이름. mcp 모듈은 workbench의 internal을 참조할 수 없어 같은 로직을 여기 둔다(WorksheetService.principal과 동일). */
    static String principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }
}
