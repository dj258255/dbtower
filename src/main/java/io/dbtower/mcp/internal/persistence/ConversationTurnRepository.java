package io.dbtower.mcp.internal.persistence;

import io.dbtower.mcp.internal.domain.ConversationTurn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationTurnRepository extends JpaRepository<ConversationTurn, Long> {

    /** id를 두 번째 정렬 키로 둔다 — 같은 밀리초에 저장된 턴의 순서가 흔들리지 않게(저장 순서 = id 순서). */
    List<ConversationTurn> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId);

    long countByConversationId(Long conversationId);

    /**
     * 대화를 지울 때 턴을 함께 지운다. 마이그레이션의 FK에도 ON DELETE CASCADE가 있지만, 테스트(H2)는
     * Hibernate가 스키마를 만들어 FK가 없다 — 어느 쪽 스키마에서도 같은 결과가 나오게 여기서 명시한다.
     */
    void deleteByConversationId(Long conversationId);
}
