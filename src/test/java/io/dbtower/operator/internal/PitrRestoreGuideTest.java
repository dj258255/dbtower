package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PITR 복원 명령 안내 문안 (Phase 2) — 생성·안내 모델이라 문자열이 곧 산출물이다.
 * 실행 순서(FULL → 로그 체인 시간순)와 시점 경계(STOPAT/--stop-datetime)가 문안에 정확히 들어가는지 검증.
 */
class PitrRestoreGuideTest {

    private final BackupTools tools = new BackupTools("d", "d", "d", "r", "r", "r", "b", "o", "w", "a", "pb", "rm", "xb", "xba", "xbv", "/tmp");

    @Test
    void MySQL_안내는_FULL_적재_후_binlog를_stop_datetime까지_재생한다() {
        DatabaseInstance mysql = new DatabaseInstance("m", DbmsType.MYSQL, "h", 3306, "sample", "u", "p");
        MySqlOperator op = new MySqlOperator(mysql, Mockito.mock(ConnectionPools.class), tools,
                Mockito.mock(HistogramSnapshotStore.class));

        String guide = op.pitrRestoreGuide("/b/full.sql",
                List.of("/b/binlog.000002", "/b/binlog.000003"), "2026-07-15 12:00:00");

        assertThat(guide).contains("mysql -u <user> -p < /b/full.sql");
        assertThat(guide).contains("--stop-datetime='2026-07-15 12:00:00'");
        // 로그 체인은 시간순으로 한 번에 재생 — 순서가 곧 정합성
        assertThat(guide.indexOf("binlog.000002")).isLessThan(guide.indexOf("binlog.000003"));
        assertThat(guide).contains("격리된 복구용").doesNotContain("null");
    }

    @Test
    void MySQL_물리_앵커는_prepare_copyback_후_binlog_info_좌표부터_재생한다() {
        DatabaseInstance mysql = new DatabaseInstance("m", DbmsType.MYSQL, "h", 3306, "sample", "u", "p");
        MySqlOperator op = new MySqlOperator(mysql, Mockito.mock(ConnectionPools.class), tools,
                Mockito.mock(HistogramSnapshotStore.class));

        String guide = op.pitrRestoreGuide("/b/mysql-physical-m.xbstream",
                List.of("/b/binlog.000005"), "2026-07-16 12:00:00");

        // 물리 산출물은 논리 적재(mysql < dump)가 아니라 prepare + copy-back 절차여야 한다
        assertThat(guide).contains("xbstream -x").contains("--prepare").contains("--copy-back");
        // 백업에 이미 든 변경의 중복 재생 방지 — binlog_info 좌표에서 시작해야 한다
        assertThat(guide).contains("xtrabackup_binlog_info").contains("--start-position");
        assertThat(guide).contains("--stop-datetime='2026-07-16 12:00:00'").doesNotContain("null");
    }

    @Test
    void MSSQL_안내는_NORECOVERY_체인_후_마지막_로그만_STOPAT_RECOVERY다() {
        DatabaseInstance mssql = new DatabaseInstance("s", DbmsType.MSSQL, "h", 1433, "orders", "u", "p");
        MsSqlOperator op = new MsSqlOperator(mssql, Mockito.mock(ConnectionPools.class), tools);

        String guide = op.pitrRestoreGuide("(server) /var/opt/mssql/data/full.bak",
                List.of("(server) /var/opt/mssql/data/log1.trn", "(server) /var/opt/mssql/data/log2.trn"),
                "2026-07-15T12:00:00");

        // (server) 접두사는 떼고 서버 관점 경로만 — 안내문은 서버에서 실행된다
        assertThat(guide).contains("FROM DISK = N'/var/opt/mssql/data/full.bak' WITH NORECOVERY");
        assertThat(guide).contains("FROM DISK = N'/var/opt/mssql/data/log1.trn' WITH NORECOVERY;");
        assertThat(guide).contains("FROM DISK = N'/var/opt/mssql/data/log2.trn' WITH STOPAT = '2026-07-15T12:00:00', RECOVERY;");
        assertThat(guide).doesNotContain("(server)");
        // 복구용 DB 이름으로 유도 — 원본을 덮지 않게
        assertThat(guide).contains("[orders_pitr]");
    }

    @Test
    void PG_안내는_앵커_종류로_분기한다_논리는_한계_물리는_공식_절차() {
        DatabaseInstance pg = new DatabaseInstance("p", DbmsType.POSTGRESQL, "h", 5432, "db", "u", "p");
        PostgresOperator op = new PostgresOperator(pg, Mockito.mock(ConnectionPools.class), tools);

        // 논리(pg_dump) 앵커 — 절차를 지어내지 않고 WAL 재생 불가 + PHYSICAL 권고
        String logical = op.pitrRestoreGuide("/b/postgres-x-full.sql", List.of("/b/wal1"), "2026-07-15 12:00:00");
        assertThat(logical).contains("논리 덤프라 WAL을 재생할 수 없다").contains("PHYSICAL");

        // 물리(basebackup) 앵커 — 공식 PITR 절차(해체 + WAL 배치 + recovery_target_time)
        String physical = op.pitrRestoreGuide("/b/postgres-basebackup-x.tar",
                List.of("/b/wal1", "/b/wal2"), "2026-07-15 12:00:00");
        assertThat(physical).contains("tar -xf /b/postgres-basebackup-x.tar");
        assertThat(physical).contains("recovery.signal");
        assertThat(physical).contains("recovery_target_time = '2026-07-15 12:00:00'");
    }

    @Test
    void Oracle_안내는_앵커_종류로_분기한다_논리는_한계_RMAN은_UNTIL_TIME() {
        DatabaseInstance oracle = new DatabaseInstance("o", DbmsType.ORACLE, "h", 1521, "FREE", "u", "p");
        OracleOperator op = new OracleOperator(oracle, Mockito.mock(ConnectionPools.class), tools);

        // Data Pump 논리 앵커 — 아카이브 재생 불가 한계 명시
        assertThat(op.pitrRestoreGuide("(server) DATA_PUMP_DIR/full.dmp", List.of(), "t"))
                .contains("아카이브 로그를 재생할 수 없다").contains("PHYSICAL");

        // RMAN 물리 앵커 — 정석 UNTIL TIME 절차 + RESETLOGS 경고
        String rman = op.pitrRestoreGuide("./backups/oracle-rman-database-x.log",
                List.of("a1", "a2"), "2026-07-15 12:00:00");
        assertThat(rman).contains("SET UNTIL TIME \"TO_DATE('2026-07-15 12:00:00'");
        assertThat(rman).contains("RESTORE DATABASE").contains("RECOVER DATABASE");
        assertThat(rman).contains("RESETLOGS");
    }
}
