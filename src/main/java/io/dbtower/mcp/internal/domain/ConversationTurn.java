package io.dbtower.mcp.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관제 AI 대화의 진단 한 턴. 스키마의 단일 권위는 V46 마이그레이션이다.
 *
 * <p>toolCalls는 ToolCallTrace 목록 JSON이되 결과 본문(resultSnippet)을 비운 사본이다 — 대상 DB에서 온 값을
 * 메타 DB에 쌓지 않기 위해서다(V46 주석). 대화와의 관계는 JPA 연관이 아니라 id 참조다(워크벤치 ChatMessage와 같은 방식).
 */
@Entity
@Table(name = "console_conversation_turn")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long conversationId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(columnDefinition = "TEXT")
    private String rootCause;

    @Column(length = 16)
    private String confidence;

    @Column(length = 40)
    private String backend;

    @Column(columnDefinition = "TEXT")
    private String toolCalls;

    @Column(nullable = false)
    private long tookMs;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public ConversationTurn(Long conversationId, String question, String answer, String rootCause,
                            String confidence, String backend, String toolCalls, long tookMs) {
        this.conversationId = conversationId;
        this.question = question;
        this.answer = answer;
        this.rootCause = rootCause;
        this.confidence = confidence;
        this.backend = backend;
        this.toolCalls = toolCalls;
        this.tookMs = tookMs;
        this.createdAt = LocalDateTime.now();
    }
}
