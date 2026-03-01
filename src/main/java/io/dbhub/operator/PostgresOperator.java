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

    public PostgresOperator(DatabaseInstance instance) {
        super(instance);
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
        String sql = """
                SELECT queryid::text AS query_id, query, calls,
                       total_exec_time AS total_ms, rows
                FROM pg_stat_statements
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
                WHERE mean_exec_time > ?
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
}
