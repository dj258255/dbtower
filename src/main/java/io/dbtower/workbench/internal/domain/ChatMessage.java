package io.dbtower.workbench.internal.domain;

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

/** 워크시트 AI 대화 한 턴. 스키마의 단일 권위는 V36 마이그레이션이다. */
@Entity
@Table(name = "workbench_chat_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    public static final String USER = "USER";
    public static final String ASSISTANT = "ASSISTANT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long worksheetId;

    @Column(nullable = false)
    private String principal;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String proposedSql;

    @Column(length = 20)
    private String tier;

    @Column(length = 40)
    private String kind;

    @Column(length = 500)
    private String unknownTables;

    @Column(nullable = false)
    private boolean valuesShared;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public ChatMessage(Long worksheetId, String principal, Long instanceId, String role, String content,
                       String proposedSql, String tier, String kind, String unknownTables, boolean valuesShared) {
        this.worksheetId = worksheetId;
        this.principal = principal;
        this.instanceId = instanceId;
        this.role = role;
        this.content = content;
        this.proposedSql = proposedSql;
        this.tier = tier;
        this.kind = kind;
        this.unknownTables = unknownTables;
        this.valuesShared = valuesShared;
        this.createdAt = LocalDateTime.now();
    }
}
