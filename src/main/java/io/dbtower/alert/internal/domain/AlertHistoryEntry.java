package io.dbtower.alert.internal.domain;

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
 * 발사된(또는 발사 시도된) 감지 알림 한 건.
 *
 * <p>다관점 감사에서 "감지 알림은 웹훅으로만 나가고 영속 이력이 없다"는 것이 문제로 지목됐다 —
 * 알림이 레이트리밋이나 전송 실패로 사라져도 사후에 확인할 방법이 없었고, 인시던트 리포트도
 * 그 한계를 인정하며 감지 알림을 타임라인에서 제외하고 있었다.
 *
 * <p>{@code status}가 FAILED면 그 알림은 전달되지 않았고 <b>쿨다운도 확정되지 않은</b> 상태다 —
 * 다음 폴에서 같은 신호가 다시 감지돼 재시도된다. 즉 FAILED 행은 유실이 아니라 재시도 대기의 기록이다.
 */
@Entity
@Table(name = "alert_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertHistoryEntry {

    public enum Status { SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 함대 전체 리포트(월간 등)는 대상 인스턴스가 없어 null이다. */
    @Column(name = "instance_id")
    private Long instanceId;

    @Column(name = "kind", nullable = false, length = 40)
    private String kind;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public AlertHistoryEntry(Long instanceId, String kind, String severity,
                             String summary, Status status, LocalDateTime occurredAt) {
        this.instanceId = instanceId;
        this.kind = kind;
        this.severity = severity;
        this.summary = summary;
        this.status = status.name();
        this.occurredAt = occurredAt;
    }
}
