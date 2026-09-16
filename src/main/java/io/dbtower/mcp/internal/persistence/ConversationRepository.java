package io.dbtower.mcp.internal.persistence;

import io.dbtower.mcp.internal.domain.Conversation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByPrincipalAndInstanceIdOrderByUpdatedAtDesc(String principal, Long instanceId,
                                                                        Pageable pageable);
}
