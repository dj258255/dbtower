package io.dbhub.operator;

/**
 * 추상 백업 정책. 사용자는 "30분 주기 전체 백업"처럼 정책만 말하고,
 * 실제 실행 구문(mysqldump / pg_basebackup / BACKUP DATABASE)은 각 Operator가 기종에 맞게 결정한다. (확장1)
 */
public record BackupPolicy(String cron, BackupType type) {

    public enum BackupType { FULL, LOG }
}
