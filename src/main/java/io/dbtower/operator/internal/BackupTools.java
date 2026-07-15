package io.dbtower.operator.internal;

/**
 * 백업·복원 검증 도구 설정. 실행 모델이 기종마다 다르다는 현실을 설정으로 흡수한다.
 * - MySQL: 클라이언트 도구(mysqldump/mysql)가 필요 — 호스트에 없으면 명령을 바꿔 우회(예: docker exec)
 * - PostgreSQL: 호스트/컨테이너의 pg_dump·psql CLI
 * - SQL Server: 서버 사이드 SQL(BACKUP DATABASE / RESTORE VERIFYONLY)이라 도구가 필요 없다
 * - MongoDB: mongodump/mongorestore CLI — 비밀번호는 --config /dev/stdin으로 stdin 전달
 * - Oracle: 서버 사이드 API(DBMS_DATAPUMP)라 도구가 필요 없다
 *
 * 복원 검증 명령(*RestoreCommand)은 백업 명령의 대칭 — 같은 실행 벡터(docker exec)로 임시/격리
 * 대상에 복원을 시도한다. 명령이 클라이언트 호출의 "접두부"만 정의하고(예: docker exec ... mysql -u root),
 * 임시 DB 생성/적재/카운트/삭제 인자는 Operator가 덧붙인다.
 */
public record BackupTools(String mysqldumpCommand, String pgDumpCommand, String mongodumpCommand,
                          String mysqlRestoreCommand, String pgRestoreCommand, String mongoRestoreCommand,
                          String mysqlBinlogCommand, String mongoOplogCommand,
                          String pgWalCommand, String oracleArchiveCommand,
                          String backupDir) {
}
