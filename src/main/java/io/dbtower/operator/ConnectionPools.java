package io.dbtower.operator;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.dbtower.registry.DatabaseInstance;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 관리 대상 인스턴스별 커넥션 풀.
 *
 * 개선 아크 1: 매 수집마다 DriverManager로 새 커넥션을 열던 방식(TCP+인증 핸드셰이크 반복)을
 * 인스턴스별 HikariCP 풀로 교체. before/after는 docs/DESIGN.md의 실측 기록 참고.
 *
 * 관리 플랫폼은 대상 DB를 "가끔" 조회하므로 풀을 작게 유지한다 — 대상 DB의 커넥션 슬롯은 서비스가
 * 써야 할 자원이라 관제 도구가 많이 점유하면 안 된다. 다만 한 인스턴스에 독립 접근자가 여럿이다
 * (SnapshotScheduler + 워커, OpsAlert, SLO, Backup, Anomaly, Regression, Advisor, Score). max=2였을 때는
 * 3번째 동시 요청부터 connectionTimeout만큼 대기하다 SQLException이 나고, SnapshotScheduler가 정상
 * 인스턴스를 죽은 대상으로 오인해 최대 16분 백오프 + 허위 경보를 냈다(B-7). 그래서 풀 상한을 설정값으로
 * 올려(기본 6) 동시 접근자 수에 맞춘다. connectionTimeout도 여유 있게(기본 5s) 둔다.
 */
@Component
public class ConnectionPools {

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    /** A9: 모든 JDBC 조회의 기본 쿼리 타임아웃(초). 진단이 대상 DB를 오래 붙잡지 않게. */
    private final int queryTimeoutSeconds;

    /** B-7: 인스턴스별 HikariCP 풀 최대 커넥션 수. 한 인스턴스의 동시 접근자(폴러·워커) 수에 맞춘다. */
    private final int maxPoolSize;

    /** B-7: 커넥션 획득 대기 상한(ms). 풀이 포화됐을 때 얼마나 기다렸다 실패로 볼지. */
    private final int connectionTimeoutMs;

    public ConnectionPools(
            @org.springframework.beans.factory.annotation.Value("${dbtower.query-timeout-seconds:15}") int queryTimeoutSeconds,
            @org.springframework.beans.factory.annotation.Value("${dbtower.pool.max-per-instance:6}") int maxPoolSize,
            @org.springframework.beans.factory.annotation.Value("${dbtower.pool.connection-timeout-ms:5000}") int connectionTimeoutMs) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxPoolSize = Math.max(1, maxPoolSize);
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public Connection getConnection(DatabaseInstance instance, String jdbcUrl) throws SQLException {
        // 등록 전 접속 검증(id 없음)은 풀을 만들지 않고 1회성 연결로 처리한다
        if (instance.getId() == null) {
            return DriverManager.getConnection(jdbcUrl, instance.getUsername(), instance.getPassword());
        }
        HikariDataSource ds = pools.computeIfAbsent(instance.getId(), id -> newPool(instance, jdbcUrl));
        return ds.getConnection();
    }

    /**
     * JdbcTemplate이 쓸 DataSource를 준다 — getConnection과 같은 자원 정책을 그대로 따른다.
     * id!=null이면 인스턴스별 HikariCP 풀(computeIfAbsent로 재사용), id==null(등록 검증)이면
     * DriverManagerDataSource로 1회용. DriverManagerDataSource는 풀링 없이 매 getConnection마다
     * 새 물리 커넥션을 열고 닫아, 기존 DriverManager 1회성 연결과 동작이 동일하다.
     */
    public DataSource getDataSource(DatabaseInstance instance, String jdbcUrl) {
        if (instance.getId() == null) {
            return new DriverManagerDataSource(jdbcUrl, instance.getUsername(), instance.getPassword());
        }
        return pools.computeIfAbsent(instance.getId(), id -> newPool(instance, jdbcUrl));
    }

    private HikariDataSource newPool(DatabaseInstance instance, String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(instance.getUsername());
        config.setPassword(instance.getPassword());
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setPoolName("dbtower-" + instance.getName());
        return new HikariDataSource(config);
    }

    /** 인스턴스 삭제 시 풀도 정리한다 — 안 하면 죽은 대상의 커넥션이 남는다 */
    public void close(Long instanceId) {
        HikariDataSource ds = pools.remove(instanceId);
        if (ds != null) {
            ds.close();
        }
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
