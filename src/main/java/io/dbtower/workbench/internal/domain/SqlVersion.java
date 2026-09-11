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

/** 워크시트의 SQL 버전 하나 — 만들어진 뒤 바뀌지 않는다(되돌리기도 새 버전). 스키마의 단일 권위는 V36이다. */
@Entity
@Table(name = "workbench_sql_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SqlVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long worksheetId;

    @Column(nullable = false)
    private int versionNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sql;

    @Column(nullable = false, length = 16)
    private String source;

    private Long chatMessageId;

    private Integer restoredFrom;

    @Column(length = 200)
    private String title;

    @Column(nullable = false)
    private String principal;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public SqlVersion(Long worksheetId, int versionNo, String sql, String source, Long chatMessageId,
                      Integer restoredFrom, String title, String principal) {
        this.worksheetId = worksheetId;
        this.versionNo = versionNo;
        this.sql = sql;
        this.source = source;
        this.chatMessageId = chatMessageId;
        this.restoredFrom = restoredFrom;
        this.title = title;
        this.principal = principal;
        this.createdAt = LocalDateTime.now();
    }
}
