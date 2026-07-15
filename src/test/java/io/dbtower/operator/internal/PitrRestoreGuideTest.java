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

    private final BackupTools tools = new BackupTools("d", "d", "d", "r", "r", "r", "b", "o", "w", "a", "/tmp");

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
    void PG_안내는_논리_덤프의_한계를_정직하게_말한다() {
        // pg_dump 논리 덤프에는 WAL을 재생할 수 없다 — 절차를 지어내지 않고 물리 베이스백업 필요를 명시
        DatabaseInstance pg = new DatabaseInstance("p", DbmsType.POSTGRESQL, "h", 5432, "db", "u", "p");
        PostgresOperator op = new PostgresOperator(pg, Mockito.mock(ConnectionPools.class), tools);
        String guide = op.pitrRestoreGuide("/b/full.sql", List.of("/b/wal1"), "2026-07-15 12:00:00");
        assertThat(guide).contains("논리 덤프라 WAL을 재생할 수 없다");
        assertThat(guide).contains("pg_basebackup");
        assertThat(guide).contains("recovery_target_time = '2026-07-15 12:00:00'");
    }

    @Test
    void 미지원_기종은_지어내지_않고_미지원을_말한다() {
        DatabaseInstance oracle = new DatabaseInstance("o", DbmsType.ORACLE, "h", 1521, "FREE", "u", "p");
        OracleOperator op = new OracleOperator(oracle, Mockito.mock(ConnectionPools.class), tools);
        assertThat(op.pitrRestoreGuide("/b/full.dmp", List.of(), "t"))
                .contains("지원하지 않습니다");
    }
}
