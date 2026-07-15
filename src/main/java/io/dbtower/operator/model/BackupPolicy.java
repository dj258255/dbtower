package io.dbtower.operator.model;

/**
 * 추상 백업 정책. 사용자는 "30분 주기 전체 백업"처럼 정책만 말하고,
 * 실제 실행 구문(mysqldump / pg_basebackup / BACKUP DATABASE)은 각 Operator가 기종에 맞게 결정한다. (확장1)
 *
 * <p>PHYSICAL(물리 전체)은 시점 복구 체인의 앵커다 — 논리 덤프(FULL)에는 로그(WAL·아카이브)를
 * 재생할 수 없는 기종(PostgreSQL·Oracle)이 있어, 그 기종의 진짜 PITR에는 물리 백업이 필요하다.
 * MySQL·MongoDB는 논리 FULL + 로그 재생이 공식 PITR 절차라 PHYSICAL이 필수는 아니다.
 */
public record BackupPolicy(String cron, BackupType type) {

    public enum BackupType { FULL, LOG, PHYSICAL }
}
