package io.dbhub.operator;

import io.dbhub.registry.DatabaseInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC 기반 Operator 공통 골격.
 *
 * 현재는 호출마다 DriverManager로 새 커넥션을 연다 — 의도적인 초기 구현이다.
 * 수집 주기가 짧아지면 연결 비용이 병목이 되므로, 인스턴스별 HikariCP 풀로 교체하며
 * before/after를 측정하는 것이 성능 개선 아크 1번이다. (docs/DESIGN.md 참고)
 */
public abstract class AbstractJdbcOperator implements DbmsOperator {

    protected final DatabaseInstance instance;

    protected AbstractJdbcOperator(DatabaseInstance instance) {
        this.instance = instance;
    }

    /** 기종별 JDBC URL */
    protected abstract String jdbcUrl();

    /** 기종별 버전 조회 쿼리 */
    protected abstract String versionSql();

    protected Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), instance.getUsername(), instance.getPassword());
    }

    @Override
    public HealthStatus health() {
        long start = System.currentTimeMillis();
        try (Connection conn = open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(versionSql())) {
            String version = rs.next() ? rs.getString(1) : "unknown";
            return HealthStatus.up(version, System.currentTimeMillis() - start);
        } catch (SQLException e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    @Override
    public void backup(BackupPolicy policy) {
        throw new UnsupportedOperationException("확장1에서 구현 예정: " + instance.getType());
    }

    @Override
    public ReplicationState replicationState() {
        throw new UnsupportedOperationException("확장2에서 구현 예정: " + instance.getType());
    }

    /** explain 대상은 SELECT만 허용한다 — 관리 플랫폼이 임의 DML을 실행하면 안 되기 때문. */
    protected void requireSelect(String sql) {
        if (sql == null || !sql.trim().toLowerCase().startsWith("select")) {
            throw new IllegalArgumentException("EXPLAIN은 SELECT 쿼리만 허용합니다");
        }
    }
}
