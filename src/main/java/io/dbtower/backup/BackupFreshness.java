package io.dbtower.backup;

import io.dbtower.registry.DbmsType;

import java.time.LocalDateTime;

/**
 * 인스턴스 하나의 백업 신선도 (Phase D7) — "지금 백업이 최신이고 복원 가능한가"를 한 줄로 담는다.
 *
 * "백업했다"가 아니라 "최신인가·복원되는가"를 본다는 게 D7의 요지다. 그래서 마지막 성공 백업 시각과
 * 그 백업의 복원 검증 상태(A7)를 함께 들고 다닌다. 성공한 백업 이력이 아예 없는 인스턴스는
 * NO_BACKUP으로 명시해 사각지대를 가린다(빈칸이 아니라 "백업 없음"이라고 말한다).
 *
 * @param instanceId    대상 인스턴스 id
 * @param instanceName  인스턴스 이름
 * @param type          기종
 * @param lastBackupAt  마지막 성공 백업 시각(없으면 null)
 * @param verifyStatus  그 백업의 복원 검증 상태 — VERIFIED/FAILED/UNSUPPORTED(검증 전이거나 이력 없으면 null)
 * @param elapsedHours  마지막 성공 백업 이후 경과 시간(시간 단위, 없으면 null)
 * @param fresh         임계 이내로 최신인가(NO_BACKUP·STALE는 false)
 * @param status        FRESH / STALE / NO_BACKUP
 * @param thresholdHours 신선 판정 임계(dbtower.backup.freshness-hours)
 */
public record BackupFreshness(Long instanceId, String instanceName, DbmsType type,
                              LocalDateTime lastBackupAt, String verifyStatus, Double elapsedHours,
                              boolean fresh, Status status, int thresholdHours) {

    /** 신선도 판정 3-값 — 빈칸 대신 사각지대(NO_BACKUP)까지 이름을 붙인다 */
    public enum Status { FRESH, STALE, NO_BACKUP }
}
