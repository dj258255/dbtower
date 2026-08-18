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
 *
 * <p>{@code verifyIsolated}는 그 복원 명령이 <b>대상 인스턴스가 아닌 별도 검증기</b>를 가리키는지에 대한
 * 운영자의 선언이다. 플랫폼은 명령 문자열만 보고 이를 알 수 없다 — {@code docker exec -i dbtower-postgres}는
 * 호스트/포트 플레이스홀더가 없지만 여전히 대상 인스턴스 자신이다. 기본값 false는 "대상에서 돈다"는 뜻이고,
 * 이때 검증 결과에 그 사실이 경고로 실린다(논리 격리는 견고하지만 디스크·IO는 운영과 공유하므로).
 */
public record BackupTools(String mysqldumpCommand, String pgDumpCommand, String mongodumpCommand,
                          String mysqlRestoreCommand, String pgRestoreCommand, String mongoRestoreCommand,
                          String mysqlBinlogCommand, String mongoOplogCommand,
                          String pgWalCommand, String oracleArchiveCommand,

                          String pgBaseBackupCommand, String oracleRmanCommand,
                          String mysqlXtrabackupCommand, String mysqlXtrabackupArgs, String mysqlXtrabackupVerifyCommand,
                          String backupDir, boolean verifyIsolated) {

    /**
     * 복원 검증 결과에 붙일 실행 위치 안내 — 별도 검증기면 안심 문구, 대상 자신이면 경고 문구.
     * 5기종이 같은 문장을 각자 쓰지 않게 여기 한 곳에 둔다.
     */
    public String verifyLocationNote() {
        return verifyIsolated
                ? " (별도 검증 인스턴스에서 복원 — 운영 자원과 분리됨)"
                : " 주의: 이 복원은 대상 인스턴스 자신에서 수행됐다 — 논리적으로는 격리돼 있지만"
                        + "(임시 이름·원본 미변경·정리 보장) 디스크와 IO는 운영과 공유한다. "
                        + "별도 검증 인스턴스로 복원 명령을 지정하고 dbtower.backup.verify-isolated=true를 켜면 이 경고는 사라진다.";
    }
}
