package io.dbhub.backup;

import io.dbhub.operator.BackupPolicy;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 인스턴스별 백업 정책. 사용자는 "N분 주기 전체 백업"이라는 추상 정책만 정하고,
 * 실제 실행 방식(mysqldump/pg_dump/BACKUP DATABASE)은 각 Operator가 기종에 맞게 결정한다.
 */
@Entity
public class BackupPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long instanceId;

    @Column(nullable = false)
    private int intervalMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BackupPolicy.BackupType type = BackupPolicy.BackupType.FULL;

    @Column(nullable = false)
    private boolean enabled = true;

    private LocalDateTime lastRunAt;

    protected BackupPolicyEntity() {
    }

    public BackupPolicyEntity(Long instanceId, int intervalMinutes, BackupPolicy.BackupType type) {
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

    public void update(int intervalMinutes, BackupPolicy.BackupType type, boolean enabled) {
        this.intervalMinutes = intervalMinutes;
        this.type = type;
        this.enabled = enabled;
    }

    public Long getId() { return id; }
    public Long getInstanceId() { return instanceId; }
    public int getIntervalMinutes() { return intervalMinutes; }
    public BackupPolicy.BackupType getType() { return type; }
    public boolean isEnabled() { return enabled; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
}
