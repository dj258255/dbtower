package io.dbtower.aiops.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 작업 접수·재시도 이벤트를 외부 큐로 넘기기 전에 메타 DB에 먼저 남기는 Outbox.
 * 선점·발행 완료는 리포지토리의 조건부 UPDATE가 한다 — 엔티티를 읽고 고쳐 쓰면 두 릴레이가 같은 행을 가져간다.
 */
@Entity
@Table(name = "ai_operation_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiOperationOutbox {

    public static final String PENDING = "PENDING";
    public static final String CLAIMED = "CLAIMED";
    public static final String PUBLISHED = "PUBLISHED";

    @Id
    @Column(length = 36)
    private String eventId;

    @Column(nullable = false, length = 36)
    private String jobId;

    @Column(nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 36)
    private String claimToken;

    private OffsetDateTime claimedUntil;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime publishedAt;

    public AiOperationOutbox(String eventId, String jobId, String eventType, String payload, OffsetDateTime now) {
        this.eventId = eventId;
        this.jobId = jobId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = PENDING;
        this.createdAt = now;
    }
}
