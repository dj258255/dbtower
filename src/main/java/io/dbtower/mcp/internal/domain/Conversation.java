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

/** 관제 AI 대화 한 건. 스키마의 단일 권위는 V46 마이그레이션이다. */
@Entity
@Table(name = "console_conversation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String principal;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Conversation(String principal, Long instanceId, String title) {
        this.principal = principal;
        this.instanceId = instanceId;
        this.title = title;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * 제목만 바꾼다 — updatedAt은 건드리지 않는다. 이 값은 "마지막 대화 시각"이라 목록 정렬의 근거다.
     * 이름을 고쳤다고 오래된 대화가 목록 맨 위로 올라오면 무엇을 보고 정렬했는지 알 수 없어진다.
     */
    public void rename(String title) {
        this.title = title;
    }

    /** 턴이 쌓였을 때 — 목록 정렬 기준을 마지막 대화 시각으로 옮긴다. */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
