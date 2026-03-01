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
 * SQL Server 어댑터.
 *
 * 통계 소스: sys.dm_exec_query_stats DMV (플랜 캐시 기반 누적 통계)
 * 실행계획: 임의 쿼리 실행 대신 플랜 캐시(sys.dm_exec_query_plan)에서 조회한다 —
 * 운영 DB에 부하를 주지 않고 실제 사용된 플랜을 보는 방식.
 */
public class MsSqlOperator extends AbstractJdbcOperator {

    public MsSqlOperator(DatabaseInstance instance) {
        super(instance);
    }

    @Override
    protected String jdbcUrl() {
        return "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;loginTimeout=3"
                .formatted(instance.getHost(), instance.getPort(), instance.getDbName());
    }

    @Override
    protected String versionSql() {
        return "SELECT @@VERSION";
    }

    @Override
    public List<QueryStat> queryStats(int limit) {
        // total_elapsed_time은 마이크로초 단위라 1000으로 나눠 ms로 환산한다
        String sql = """
                SELECT TOP (?)
                       CONVERT(varchar(64), qs.query_hash, 1) AS query_id,
                       SUBSTRING(st.text, 1, 2000) AS query_text,
                       qs.execution_count,
                       qs.total_elapsed_time / 1000.0 AS total_ms,
                       qs.total_logical_reads
                FROM sys.dm_exec_query_stats qs
                CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                ORDER BY qs.total_elapsed_time DESC
                """;
        List<QueryStat> stats = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.add(new QueryStat(
                            rs.getString("query_id"),
                            rs.getString("query_text"),
                            rs.getLong("execution_count"),
                            rs.getDouble("total_ms"),
                            rs.getLong("total_logical_reads")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("MSSQL 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
        return stats;
    }

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        String sql = """
                SELECT TOP (?)
                       SUBSTRING(st.text, 1, 2000) AS query_text,
                       qs.total_elapsed_time / qs.execution_count / 1000.0 AS avg_ms,
                       qs.total_logical_reads / qs.execution_count AS avg_reads
                FROM sys.dm_exec_query_stats qs
                CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                WHERE qs.execution_count > 0
                ORDER BY avg_ms DESC
                """;
        List<SlowQuery> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SlowQuery(
                            rs.getString("query_text"),
                            rs.getDouble("avg_ms"),
                            rs.getLong("avg_reads"),
                            LocalDateTime.now().toString()));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("MSSQL 슬로우 쿼리 조회 실패: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public String explain(String sql) {
        requireSelect(sql);
        // 쿼리를 직접 실행하지 않고 플랜 캐시에서 유사 텍스트의 실제 사용 플랜(XML)을 찾는다
        String planSql = """
                SELECT TOP 1 qp.query_plan
                FROM sys.dm_exec_query_stats qs
                CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                CROSS APPLY sys.dm_exec_query_plan(qs.plan_handle) qp
                WHERE st.text LIKE ?
                ORDER BY qs.last_execution_time DESC
                """;
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(planSql)) {
            String prefix = sql.length() > 100 ? sql.substring(0, 100) : sql;
            ps.setString(1, "%" + prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return "플랜 캐시에서 해당 쿼리를 찾지 못했습니다. 쿼리가 한 번 이상 실행된 뒤 다시 시도하세요.";
            }
        } catch (SQLException e) {
            throw new OperatorException("MSSQL 실행계획 조회 실패: " + e.getMessage(), e);
        }
    }
}
