package io.dbtower.workbench.internal.persistence;

import io.dbtower.workbench.internal.domain.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByWorksheetIdOrderByCreatedAtAsc(Long worksheetId);

    List<ChatMessage> findByWorksheetIdOrderByCreatedAtDesc(Long worksheetId, Pageable pageable);
}
