package io.dbhub.operator;

import io.dbhub.registry.DatabaseInstance;

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

    public MySqlOperator(DatabaseInstance instance, ConnectionPools pools) {
        super(instance, pools);
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
}
