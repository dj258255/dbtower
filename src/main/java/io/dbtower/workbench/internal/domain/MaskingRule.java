package io.dbtower.workbench.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 컬럼 이름 glob 하나에 대한 마스킹 규칙. instanceId가 null이면 전 인스턴스 공통(V35가 기본 규칙을 넣는다). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaskingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long instanceId;

    @Column(nullable = false, length = 100)
    private String columnPattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MaskingStrategy strategy;

    @Column(length = 200)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public MaskingRule(Long instanceId, String columnPattern, MaskingStrategy strategy, String note) {
        this.instanceId = instanceId;
        this.columnPattern = columnPattern;
        this.strategy = strategy;
        this.note = note;
        this.createdAt = LocalDateTime.now();
    }
}
