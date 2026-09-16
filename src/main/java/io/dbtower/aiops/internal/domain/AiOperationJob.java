package io.dbtower.aiops.internal.domain;

import io.dbtower.aiops.AiOperationStatus;
import io.dbtower.aiops.AiOperationTrigger;
import io.dbtower.aiops.AiOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * AI 운영 작업의 영속 상태. 전이 규칙은 {@link AiOperationStatus}의 표 하나만 따른다.
 *
 * <p>동시성은 @Version으로 막는다 — 같은 이벤트가 두 실행기에 배달돼 동시에 선점하면 한쪽 커밋이 낙관적 락 충돌로
 * 실패한다. 선점 이후 단계는 리스 토큰으로 확인해, 늦게 도착한 실행기가 남의 작업을 진행시키지 못하게 한다.</p>
 */
@Entity
@Table(name = "ai_operation_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiOperationJob {

    @Id
    @Column(length = 36)
    private String jobId;

    @Column(nullable = false, unique = true, length = 120)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AiOperationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 20)
    private AiOperationTrigger trigger;

    @Column(nullable = false, length = 200)
    private String requester;

    @Column(nullable = false, length = 200)
    private String submittedBy;

    @Column(length = 100)
    private String scopeTeam;

    private Long instanceId;

    @Column(length = 20)
    private String instanceType;

    @Column(nullable = false)
    private OffsetDateTime windowFrom;

    @Column(nullable = false)
    private OffsetDateTime windowTo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiOperationStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(length = 36)
    private String leaseToken;

    private OffsetDateTime leaseUntil;

    @Column(length = 100)
    private String replyChannel;

    @Column(length = 100)
    private String replyThread;

    private OffsetDateTime notifiedAt;

    @Column(nullable = false)
    private OffsetDateTime requestedAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Column(length = 500)
    private String failureReason;

    @Version
    private long version;

    public AiOperationJob(String jobId, String requestId, AiOperationType type, AiOperationTrigger trigger,
                          String requester, String submittedBy, String scopeTeam, Long instanceId, String instanceType,
                          OffsetDateTime windowFrom, OffsetDateTime windowTo, String prompt,
                          String replyChannel, String replyThread, OffsetDateTime now) {
        this.jobId = jobId;
        this.requestId = requestId;
        this.type = type;
        this.trigger = trigger;
        this.requester = requester;
        this.submittedBy = submittedBy;
        this.scopeTeam = scopeTeam;
        this.instanceId = instanceId;
        this.instanceType = instanceType;
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.prompt = prompt;
        this.replyChannel = replyChannel;
        this.replyThread = replyThread;
        this.status = AiOperationStatus.RECEIVED;
        this.attempt = 1;
        this.requestedAt = now;
        this.updatedAt = now;
    }

    /** 실행기 선점 — RECEIVED에서만 가능하고, 이후 단계는 여기서 받은 토큰으로만 진행한다. */
    public void claim(String token, OffsetDateTime now, OffsetDateTime leaseUntil) {
        move(AiOperationStatus.AUTHORIZED, now);
        this.leaseToken = token;
        this.leaseUntil = leaseUntil;
    }

    /**
     * 리스를 쥔 실행기의 단계 전이. 전이할 때마다 리스를 연장해, 느린 단계가 리퍼에게 잘리지 않게 한다.
     *
     * <p>같은 단계로의 재진입은 허용한다(리스 토큰이 맞을 때만). 실행기가 사실 수집 중 DBTower의 일시 오류를 받으면 큐가
     * 메시지를 다시 배달하는데, 그때 이미 COLLECTING이라고 막으면 리스 만료까지 기다렸다가 실패로 끝난다 — 일시 오류 한 번이
     * 작업 실패가 된다. 재진입한 단계는 처음부터 다시 한다(사실 재수집, 분석 재호출).</p>
     */
    public void advance(String token, AiOperationStatus next, OffsetDateTime now, OffsetDateTime leaseUntil) {
        requireLease(token);
        if (status != next) {
            move(next, now);
        } else if (status.terminal()) {
            throw new IllegalStateException("종료된 작업은 다시 진행할 수 없습니다: " + status);
        }
        this.updatedAt = now;
        this.leaseUntil = leaseUntil;
    }

    /**
     * 선점 순간 범위 확인에 실패해 FAILED가 된 작업에 알림 전용 토큰을 준다. 실행기가 요청자에게 실패를 알리고 알림 시각을
     * 남기려면 토큰이 필요하다 — 전이 표상 FAILED에서는 재시도 말고 나갈 곳이 없어, 이 토큰으로는 알림 기록 말고 할 수 있는 게 없다.
     */
    public void grantNotificationLease(String token) {
        if (status != AiOperationStatus.FAILED) {
            throw new IllegalStateException("실패한 작업에만 알림 토큰을 줍니다");
        }
        this.leaseToken = token;
    }

    public void requireLease(String token) {
        if (leaseToken == null || !leaseToken.equals(token)) {
            throw new IllegalStateException("이 작업의 리스를 쥔 실행기가 아닙니다");
        }
    }

    public void fail(String reason, OffsetDateTime now) {
        move(AiOperationStatus.FAILED, now);
        this.failureReason = reason == null || reason.isBlank()
                ? "알 수 없는 오류" : reason.substring(0, Math.min(reason.length(), 500));
        this.leaseUntil = null;
    }

    public void cancel(OffsetDateTime now) {
        move(AiOperationStatus.CANCELLED, now);
        this.leaseUntil = null;
    }

    public void complete(OffsetDateTime now) {
        move(AiOperationStatus.COMPLETED, now);
        this.leaseUntil = null;
    }

    /** 실패한 작업을 새 시도로 되돌린다. 이전 리스 토큰을 버려 옛 실행기의 늦은 호출이 새 시도를 건드리지 못하게 한다. */
    public void retry(OffsetDateTime now) {
        move(AiOperationStatus.RECEIVED, now);
        this.attempt++;
        this.leaseToken = null;
        this.leaseUntil = null;
        this.failureReason = null;
        this.notifiedAt = null;
    }

    public void markNotified(OffsetDateTime now) {
        this.notifiedAt = now;
        this.updatedAt = now;
    }

    private void move(AiOperationStatus next, OffsetDateTime now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("허용되지 않는 상태 전이입니다: " + status + " -> " + next);
        }
        this.status = next;
        this.updatedAt = Objects.requireNonNull(now);
    }
}
