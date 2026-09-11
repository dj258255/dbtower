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

/** 인스턴스 아래 사람이 여는 작업 단위(TOI의 페이지에 해당). 스키마의 단일 권위는 V36 마이그레이션이다. */
@Entity
@Table(name = "workbench_worksheet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Worksheet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String principal;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String currentSql;

    @Column(nullable = false)
    private boolean archived;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Worksheet(String principal, Long instanceId, String title) {
        this.principal = principal;
        this.instanceId = instanceId;
        this.title = title;
        this.currentSql = "";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void rename(String title) {
        this.title = title;
        touch();
    }

    public void updateSql(String sql) {
        this.currentSql = sql == null ? "" : sql;
        touch();
    }

    public void archive() {
        this.archived = true;
        touch();
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
