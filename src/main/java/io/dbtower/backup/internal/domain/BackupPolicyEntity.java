package io.dbtower.backup.internal.domain;

import io.dbtower.operator.model.BackupPolicy.BackupType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * (인스턴스, 타입)별 백업 정책. 사용자는 "N분 주기 전체 백업"이라는 추상 정책만 정하고,
 * 실제 실행 방식(mysqldump/pg_dump/BACKUP DATABASE)은 각 Operator가 기종에 맞게 결정한다.
 *
 * 타입별로 정책이 따로인 이유(V23) — 현업 정석은 "느린 FULL 앵커 + 촘촘한 LOG 체인" 병행이다.
 * RPO(유실 허용)는 LOG 주기가, RTO(복구 시간)는 FULL 최신성이 정하므로 두 주기는 독립이어야 한다.
 * 인스턴스당 1정책이던 시절엔 둘 중 하나를 수동 실행에 의존했다(사람이 기억해야 작동하는 운영).
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_backup_policy_instance_type",
        columnNames = {"instance_id", "type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BackupPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false)
    private int intervalMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BackupType type = BackupType.FULL;

    @Column(nullable = false)
    private boolean enabled = true;

    private LocalDateTime lastRunAt;

    public BackupPolicyEntity(Long instanceId, int intervalMinutes, BackupType type) {
        this.instanceId = instanceId;
        this.intervalMinutes = intervalMinutes;
        this.type = type;
    }

    public boolean isDue(LocalDateTime now) {
        return enabled && (lastRunAt == null || lastRunAt.plusMinutes(intervalMinutes).isBefore(now));
    }

    public void markRun(LocalDateTime at) {
        this.lastRunAt = at;
    }

    public void update(int intervalMinutes, BackupType type, boolean enabled) {
        this.intervalMinutes = intervalMinutes;
        this.type = type;
        this.enabled = enabled;
    }
}
