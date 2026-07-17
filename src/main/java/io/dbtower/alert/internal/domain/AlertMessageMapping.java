package io.dbtower.alert.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Discord 알림 메시지 id ↔ 대상 인스턴스 매핑 한 건 (Gateway 봇의 이모지 트리거용).
 *
 * PK가 메시지 id인 이유 — 조회가 항상 "이 메시지의 대상은?" 단건이고, 같은 메시지가 두 번
 * 기록되면(웹훅 재시도) 마지막 기록이 이기면 된다(내용이 같으므로 무해).
 */
@Entity
@Table(name = "alert_message_index")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertMessageMapping {

    @Id
    @Column(name = "message_id", length = 32)
    private String messageId;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 반응 진단을 이미 수행한 시각 — 재시작 후 보충 스캔이 같은 알림을 중복 진단하지 않게 한다. */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    public AlertMessageMapping(String messageId, Long instanceId, LocalDateTime createdAt) {
        this.messageId = messageId;
        this.instanceId = instanceId;
        this.createdAt = createdAt;
    }

    public void markProcessed(LocalDateTime at) {
        this.processedAt = at;
    }
}
