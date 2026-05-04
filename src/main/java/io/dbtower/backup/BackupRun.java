package io.dbtower.backup;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 백업 실행 이력 한 건 — 성공/실패와 산출물 위치를 남긴다 */
@Entity
public class BackupRun {

    public enum Status { SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(length = 1000)
    private String detail;

    protected BackupRun() {
    }

    public BackupRun(Long instanceId, LocalDateTime startedAt, long durationMs, Status status, String detail) {
        this.instanceId = instanceId;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.status = status;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getInstanceId() { return instanceId; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public long getDurationMs() { return durationMs; }
    public Status getStatus() { return status; }
    public String getDetail() { return detail; }
}
