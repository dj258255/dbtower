package io.dbtower.operator;

/**
 * 백업 도구 설정. 백업 실행 모델이 기종마다 다르다는 현실을 설정으로 흡수한다.
 * - MySQL: 클라이언트 도구(mysqldump)가 필요 — 호스트에 없으면 명령을 바꿔 우회(예: docker exec)
 * - PostgreSQL: 호스트의 pg_dump CLI
 * - SQL Server: 서버 사이드 SQL(BACKUP DATABASE)이라 도구가 필요 없다
 */
public record BackupTools(String mysqldumpCommand, String pgDumpCommand, String backupDir) {
}
