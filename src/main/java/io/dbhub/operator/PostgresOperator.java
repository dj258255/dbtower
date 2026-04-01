package io.dbhub.operator;

import io.dbhub.registry.DatabaseInstance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 어댑터.
 *
 * 통계 소스: pg_stat_statements (shared_preload_libraries 필요 — docker-compose에서 설정)
 * MySQL과 달리 쿼리 파싱 결과 기반으로 정규화하므로 digest 길이 이슈가 없다.
 */
public class PostgresOperator extends AbstractJdbcOperator {

    /** 통계 기반 슬로우 쿼리 판정 임계값(평균 수행시간 ms) */
    private static final double SLOW_MEAN_MS = 500.0;

    public PostgresOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools) {
        super(instance, pools, backupTools);
    }

    /** PostgreSQL 백업 = 호스트 CLI(pg_dump) 실행 모델. 비밀번호는 인자가 아니라 PGPASSWORD 환경변수로 */
    @Override
    public BackupResult backup(BackupPolicy policy) {
        if (policy.type() == BackupPolicy.BackupType.LOG) {
            throw new UnsupportedOperationException("PostgreSQL 로그 백업은 WAL 아카이빙으로 별도 구성 필요");
        }
        java.nio.file.Path out = java.nio.file.Path.of(backupTools.backupDir(),
                "postgres-%s-%s.sql".formatted(safeFileName(instance.getName()), backupTimestamp()));
        return runCliBackup(renderCommand(backupTools.pgDumpCommand()),
                java.util.Map.of("PGPASSWORD", instance.getPassword()), out);
    }

    @Override
    protected String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s?connectTimeout=3&socketTimeout=15"
                .formatted(instance.getHost(), instance.getPort(), instance.getDbName());
    }

    @Override
    protected String versionSql() {
        return "SELECT version()";
    }

    @Override
    public List<QueryStat> queryStats(int limit) {
        // pg_stat_statements는 클러스터 전역 뷰라 dbid로 현재 DB만 필터해야 한다.
        // 안 하면 같은 클러스터의 다른 데이터베이스 쿼리까지 섞여 통계가 오염된다.
        String sql = """
                SELECT queryid::text AS query_id, query, calls,
                       total_exec_time AS total_ms, rows
                FROM pg_stat_statements
                WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                ORDER BY total_exec_time DESC
                LIMIT ?
                """;
        List<QueryStat> stats = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.add(new QueryStat(
                            rs.getString("query_id"),
                            rs.getString("query"),
                            rs.getLong("calls"),
                            rs.getDouble("total_ms"),
                            rs.getLong("rows")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("PostgreSQL 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
        return stats;
    }

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        // PG는 로그 파일 파싱 대신 pg_stat_statements의 평균 수행시간으로 판정한다
        String sql = """
                SELECT query, mean_exec_time, rows
                FROM pg_stat_statements
                WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                  AND mean_exec_time > ?
                ORDER BY mean_exec_time DESC
                LIMIT ?
                """;
        List<SlowQuery> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, SLOW_MEAN_MS);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SlowQuery(
                            rs.getString("query"),
                            rs.getDouble("mean_exec_time"),
                            rs.getLong("rows"),
                            LocalDateTime.now().toString()));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("PostgreSQL 슬로우 쿼리 조회 실패: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<TableStat> tableStats(int limit) {
        // n_live_tup은 autovacuum 통계 기반 추정치
        String sql = """
                SELECT relname,
                       n_live_tup,
                       pg_relation_size(relid) AS data_bytes,
                       pg_indexes_size(relid) AS index_bytes
                FROM pg_stat_user_tables
                ORDER BY pg_total_relation_size(relid) DESC
                LIMIT ?
                """;
        List<TableStat> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TableStat(
                            rs.getString("relname"),
                            rs.getLong("n_live_tup"),
                            rs.getLong("data_bytes"),
                            rs.getLong("index_bytes")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("PostgreSQL 테이블 통계 조회 실패: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public String explain(String sql) {
        requireSelect(sql);
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("EXPLAIN (FORMAT JSON) " + sql);
             ResultSet rs = ps.executeQuery()) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1));
            }
            return sb.toString();
        } catch (SQLException e) {
            throw new OperatorException("PostgreSQL EXPLAIN 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 복제 상태 — pg_is_in_recovery()면 레플리카(재생 지연 = now - 마지막 재생 시각),
     * 아니면 pg_stat_replication의 연결 수로 프라이머리/단독을 구분한다.
     */
    @Override
    public ReplicationState replicationState() {
        try (Connection conn = open()) {
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT pg_is_in_recovery() AS in_recovery, " +
                    "COALESCE(EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp())), 0) AS lag_sec")) {
                rs.next();
                if (rs.getBoolean("in_recovery")) {
                    return new ReplicationState("REPLICA", rs.getDouble("lag_sec"), "recovery 모드");
                }
            }
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) AS replicas FROM pg_stat_replication")) {
                rs.next();
                int replicas = rs.getInt("replicas");
                return replicas > 0
                        ? new ReplicationState("PRIMARY", 0, "replicas=" + replicas)
                        : new ReplicationState("STANDALONE", 0, "복제 구성 없음");
            }
        } catch (SQLException e) {
            throw new OperatorException("PostgreSQL 복제 상태 조회 실패: " + e.getMessage(), e);
        }
    }
}
