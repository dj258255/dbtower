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

    /** 성공 시 산출물 위치 — 나중에 "가장 최근 백업"을 검증할 때 detail 파싱 없이 쓰기 위해 별도 보관 (실패 시 null) */
    @Column(length = 1000)
    private String location;

    /** 복원 검증 결과 — VERIFIED/FAILED/UNSUPPORTED (아직 검증 전이면 null). enum 이름 문자열로 저장 */
    @Column(length = 20)
    private String verifyStatus;

    private LocalDateTime verifiedAt;

    protected BackupRun() {
    }

    public BackupRun(Long instanceId, LocalDateTime startedAt, long durationMs, Status status, String detail) {
        this(instanceId, startedAt, durationMs, status, detail, null);
    }

    public BackupRun(Long instanceId, LocalDateTime startedAt, long durationMs, Status status,
                     String detail, String location) {
        this.instanceId = instanceId;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.status = status;
        this.detail = detail;
        this.location = location;
    }

    /** 복원 검증 결과를 이 이력에 기록한다 — 백업이 실제로 복원 가능한지까지 남겨야 "0 errors"가 완성된다 */
    public void recordVerification(String verifyStatus, LocalDateTime verifiedAt) {
        this.verifyStatus = verifyStatus;
        this.verifiedAt = verifiedAt;
    }

    public Long getId() { return id; }
    public Long getInstanceId() { return instanceId; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public long getDurationMs() { return durationMs; }
    public Status getStatus() { return status; }
    public String getDetail() { return detail; }
    public String getLocation() { return location; }
    public String getVerifyStatus() { return verifyStatus; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
}
