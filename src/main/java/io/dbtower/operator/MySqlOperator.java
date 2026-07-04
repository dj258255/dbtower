package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 어댑터.
 *
 * 통계 소스: performance_schema.events_statements_summary_by_digest
 * 주의 — digest는 max_digest_length(기본 1024B)까지만 정규화되므로, 앞부분이 같은 긴 쿼리들이
 * 하나로 뭉개질 수 있다. docker-compose에서 4096으로 늘려 운영한다. (당근 KDMS 사례와 동일 이슈)
 */
public class MySqlOperator extends AbstractJdbcOperator {

    public MySqlOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools) {
        super(instance, pools, backupTools);
    }

    /**
     * MySQL 백업 = 클라이언트 도구(mysqldump) 실행 모델.
     * --single-transaction: InnoDB에서 락 없이 일관된 스냅샷으로 덤프 (MVCC 활용)
     */
    @Override
    public BackupResult backup(BackupPolicy policy) {
        if (policy.type() == BackupPolicy.BackupType.LOG) {
            throw new UnsupportedOperationException("MySQL 로그 백업은 binlog 아카이빙으로 별도 구성 필요");
        }
        java.nio.file.Path out = java.nio.file.Path.of(backupTools.backupDir(),
                "mysql-%s-%s.sql".formatted(safeFileName(instance.getName()), backupTimestamp()));
        // 비밀번호는 argv가 아니라 MYSQL_PWD 환경변수로 — ps로 노출되지 않게
        return runCliBackup(renderCommand(backupTools.mysqldumpCommand()),
                java.util.Map.of("MYSQL_PWD", instance.getPassword()), out);
    }

    /**
     * MySQL 복원 검증 = 덤프를 격리된 임시 DB에 실제로 복원해 보는 진짜 restore test.
     * 덤프의 CREATE DATABASE/USE 행을 제거해 원본이 아니라 임시 DB로만 적재하고(핵심 안전장치),
     * 성공 시 복원된 테이블 수로 sanity check, 마지막에 임시 DB를 삭제한다. 정리가 실패해도 원본은 무해.
     */
    @Override
    public RestoreVerification verifyRestore(String location) {
        java.nio.file.Path dump = java.nio.file.Path.of(location);
        if (!java.nio.file.Files.isRegularFile(dump)) {
            return RestoreVerification.failed("덤프 파일을 찾을 수 없습니다: " + location);
        }
        String target = RestoreSupport.verifyTargetName();
        RestoreSupport.requireSafeName(target);
        java.util.Map<String, String> env = java.util.Map.of("MYSQL_PWD", instance.getPassword());
        java.util.List<String> base = renderCommand(backupTools.mysqlRestoreCommand());
        boolean created = false;
        try {
            RestoreSupport.ExecResult create = RestoreSupport.exec(
                    RestoreSupport.concat(base, "-e", "CREATE DATABASE `" + target + "`"), env, null);
            if (!create.ok()) {
                return RestoreVerification.failed("임시 DB 생성 실패: " + create.errorTail());
            }
            created = true;
            RestoreSupport.ExecResult load = RestoreSupport.exec(
                    RestoreSupport.concat(base, target), env, RestoreSupport.stripDatabaseSelection(dump));
            if (!load.ok()) {
                return RestoreVerification.failed("덤프 복원 실패: " + load.errorTail());
            }
            RestoreSupport.ExecResult count = RestoreSupport.exec(RestoreSupport.concat(base, "-N", "-B", "-e",
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + target + "'"), env, null);
            return RestoreVerification.verified(
                    "임시 DB로 덤프 복원 성공 (mysql 클라이언트, target=" + target + ")",
                    RestoreSupport.parseCount(count));
        } finally {
            if (created) {
                // 정리 실패해도 원본과 무관 — best effort로 임시 DB만 삭제
                RestoreSupport.exec(RestoreSupport.concat(base, "-e",
                        "DROP DATABASE IF EXISTS `" + target + "`"), env, null);
            }
        }
    }

    @Override
    protected String jdbcUrl() {
        return "jdbc:mysql://%s:%d/%s?connectTimeout=3000&socketTimeout=15000"
                .formatted(instance.getHost(), instance.getPort(), instance.getDbName());
    }

    @Override
    protected String versionSql() {
        return "SELECT VERSION()";
    }

    @Override
    public List<QueryStat> queryStats(int limit) {
        // TIMER_WAIT 계열은 피코초 단위라 1e9로 나눠 ms로 환산한다
        String sql = """
                SELECT DIGEST, DIGEST_TEXT, COUNT_STAR,
                       SUM_TIMER_WAIT / 1000000000 AS total_ms,
                       SUM_ROWS_EXAMINED
                FROM performance_schema.events_statements_summary_by_digest
                WHERE DIGEST_TEXT IS NOT NULL
                ORDER BY SUM_TIMER_WAIT DESC
                LIMIT ?
                """;
        List<QueryStat> stats = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.add(new QueryStat(
                            rs.getString("DIGEST"),
                            rs.getString("DIGEST_TEXT"),
                            rs.getLong("COUNT_STAR"),
                            rs.getDouble("total_ms"),
                            rs.getLong("SUM_ROWS_EXAMINED")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("MySQL 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
        return stats;
    }

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        // docker-compose에서 slow_query_log=ON, log_output=TABLE로 켜두어 mysql.slow_log에서 직접 조회한다
        String sql = """
                SELECT CONVERT(sql_text USING utf8mb4) AS sql_text,
                       TIME_TO_SEC(query_time) * 1000 AS elapsed_ms,
                       rows_examined,
                       start_time
                FROM mysql.slow_log
                ORDER BY start_time DESC
                LIMIT ?
                """;
        List<SlowQuery> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SlowQuery(
                            rs.getString("sql_text"),
                            rs.getDouble("elapsed_ms"),
                            rs.getLong("rows_examined"),
                            rs.getString("start_time")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("MySQL 슬로우 쿼리 조회 실패: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<TableStat> tableStats(int limit) {
        // TABLE_ROWS는 InnoDB 통계 기반 추정치다 (정확한 COUNT 아님)
        String sql = """
                SELECT TABLE_NAME, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ?
                ORDER BY DATA_LENGTH + INDEX_LENGTH DESC
                LIMIT ?
                """;
        List<TableStat> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, instance.getDbName());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TableStat(
                            rs.getString("TABLE_NAME"),
                            rs.getLong("TABLE_ROWS"),
                            rs.getLong("DATA_LENGTH"),
                            rs.getLong("INDEX_LENGTH")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("MySQL 테이블 통계 조회 실패: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public String explain(String sql) {
        requireSelect(sql);
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("EXPLAIN FORMAT=JSON " + sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : "{}";
        } catch (SQLException e) {
            throw new OperatorException("MySQL EXPLAIN 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 대기 이벤트 — performance_schema.events_waits_summary_global_by_event_name (서버 기동 이후 누적).
     *
     * 보이는 범위는 setup_instruments에 달려 있다: 기본값은 wait/io·wait/lock만 켜져 있고
     * wait/synch(뮤텍스·rwlock 342종)는 꺼져 있다 — 대상 컨테이너에서 실측 확인(2026-07-04).
     * 그래서 이 결과는 "IO/Lock 중심의 부분 뷰"이며, 그 사실을 응답에 안내 행으로 정직하게 싣는다.
     * UPDATE performance_schema.setup_instruments로 동적 활성이 가능하지만 그것은 대상 DB의
     * 설정 변경이다 — 관제 도구는 읽기만 한다는 원칙(AGENTS.md)에 따라 여기서는 하지 않는다.
     */
    @Override
    public List<WaitEvent> waitEvents(int limit) {
        // TIMER_WAIT 계열은 피코초 단위 -> 1e9로 나눠 ms. idle은 클라이언트가 안 보내고 노는 시간이라 제외
        String sql = """
                SELECT EVENT_NAME, COUNT_STAR,
                       SUM_TIMER_WAIT / 1000000000 AS total_ms
                FROM performance_schema.events_waits_summary_global_by_event_name
                WHERE COUNT_STAR > 0
                  AND EVENT_NAME <> 'idle'
                ORDER BY SUM_TIMER_WAIT DESC
                LIMIT ?
                """;
        List<WaitEvent> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("EVENT_NAME");
                    result.add(new WaitEvent(name, mysqlWaitCategory(name),
                            rs.getLong("COUNT_STAR"), rs.getDouble("total_ms")));
                }
            }
            // 꺼진 instrument 계열 수를 세서 "부분 뷰"임을 데이터로 알린다 — 없는 대기를 없다고 오독하지 않게
            try (PreparedStatement ps2 = conn.prepareStatement(
                    "SELECT COUNT(*) FROM performance_schema.setup_instruments "
                            + "WHERE NAME LIKE 'wait/%' AND ENABLED = 'NO'");
                 ResultSet rs2 = ps2.executeQuery()) {
                if (rs2.next() && rs2.getLong(1) > 0) {
                    result.add(new WaitEvent(
                            "(안내) 비활성 wait instrument " + rs2.getLong(1)
                                    + "종은 집계에 없음 — setup_instruments 기본값 기준의 부분 뷰",
                            "INFO", 0, 0));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("MySQL 대기 이벤트 조회 실패: " + e.getMessage(), e);
        }
        return result;
    }

    /** 이벤트 이름의 둘째 구획이 곧 분류다 — wait/io/file/... -> io, wait/lock/table/... -> lock */
    private static String mysqlWaitCategory(String eventName) {
        String[] parts = eventName.split("/");
        return parts.length >= 2 ? parts[1] : eventName;
    }

    /** 복제 상태 — 레플리카면 SHOW REPLICA STATUS에 행이 있고, Seconds_Behind_Source가 지연이다 */
    @Override
    public ReplicationState replicationState() {
        try (Connection conn = open()) {
            try (ResultSet rs = conn.createStatement().executeQuery("SHOW REPLICA STATUS")) {
                if (rs.next()) {
                    double lag = rs.getObject("Seconds_Behind_Source") == null
                            ? -1 : rs.getDouble("Seconds_Behind_Source");
                    return new ReplicationState("REPLICA", lag,
                            "source=" + rs.getString("Source_Host") + ":" + rs.getInt("Source_Port"));
                }
            }
            try (ResultSet rs = conn.createStatement().executeQuery("SHOW REPLICAS")) {
                int replicas = 0;
                while (rs.next()) {
                    replicas++;
                }
                return replicas > 0
                        ? new ReplicationState("PRIMARY", 0, "replicas=" + replicas)
                        : new ReplicationState("STANDALONE", 0, "복제 구성 없음");
            }
        } catch (SQLException e) {
            throw new OperatorException("MySQL 복제 상태 조회 실패: " + e.getMessage(), e);
        }
    }
}
