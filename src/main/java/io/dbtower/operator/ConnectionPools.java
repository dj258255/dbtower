package io.dbtower.operator;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.DatabaseInstance;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

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
 *
 * 커넥션 온디맨드 (Phase 4): minimumIdle=0 — 안 쓰는 인스턴스(격리·백오프·저빈도)의 유휴 커넥션을
 * 대상 DB에 상시 꽂아두지 않는다(관제 대상 수만큼 유휴 커넥션이 늘던 구조 해소). 활발한 인스턴스는
 * 수집 주기(60초)마다 커넥션을 재사용하므로 idleTimeout(기본 10분) 안에서 따뜻하게 유지된다.
 * <b>하한 가드</b>: idleTimeout이 수집 주기보다 짧으면 매 틱 물리 재연결 폭탄이라
 * max(설정값, 수집주기+30s)로 강제한다. 장기 미사용 풀은 sweep이 통째로 닫는다(LRU) —
 * 풀 객체 자체(하우스키핑 스레드·메모리)도 회수한다.
 */
@Component
public class ConnectionPools {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPools.class);

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    /**
     * 워크벤치 콘솔 계정 풀 — 모니터 풀과 절대 섞지 않는다. 키는 "인스턴스id:용도"(READ/WRITE).
     * 사람이 여는 조회라 수집기처럼 동시 접근자가 많지 않아 상한을 작게 둔다(대상 DB 슬롯은 서비스의 자원).
     */
    private final Map<String, HikariDataSource> consolePools = new ConcurrentHashMap<>();
    private final Map<String, Long> consoleLastUsedMs = new ConcurrentHashMap<>();
    private static final int CONSOLE_POOL_MAX = 2;

    /** Vault 동적 자격증명(선택) — username이 vault: 접두인 인스턴스의 실제 계정을 접속 시점에 해석 */
    private final VaultCredentials vaultCredentials;

    /** 풀별 마지막 사용 시각(ms) — LRU 정리 판정용. getConnection/getDataSource가 갱신한다. */
    private final Map<Long, Long> lastUsedMs = new ConcurrentHashMap<>();

    /** A9: 모든 JDBC 조회의 기본 쿼리 타임아웃(초). 진단이 대상 DB를 오래 붙잡지 않게. */
    private final int queryTimeoutSeconds;

    /** B-7: 인스턴스별 HikariCP 풀 최대 커넥션 수. 한 인스턴스의 동시 접근자(폴러·워커) 수에 맞춘다. */
    private final int maxPoolSize;

    /** B-7: 커넥션 획득 대기 상한(ms). 풀이 포화됐을 때 얼마나 기다렸다 실패로 볼지. */
    private final int connectionTimeoutMs;

    /** 유휴 커넥션 유지 시간(ms) — 하한 가드 적용 후 값. */
    private final long idleTimeoutMs;

    /** 커넥션 최대 수명(ms) — 방화벽/LB의 조용한 끊김보다 먼저 우리가 갈아끼운다(Hikari 권장). */
    private final long maxLifetimeMs;

    /** 이 시간(분) 동안 안 쓰인 풀은 통째로 닫는다. 0 이하 = 끔(풀 영구 유지 — 현행과 동일). */
    private final long evictAfterMinutes;

    public ConnectionPools(
            VaultCredentials vaultCredentials,
            @Value("${dbtower.query-timeout-seconds:15}") int queryTimeoutSeconds,
            @Value("${dbtower.pool.max-per-instance:6}") int maxPoolSize,
            @Value("${dbtower.pool.connection-timeout-ms:5000}") int connectionTimeoutMs,
            @Value("${dbtower.pool.idle-timeout-ms:600000}") long idleTimeoutMs,
            @Value("${dbtower.pool.max-lifetime-ms:1800000}") long maxLifetimeMs,
            @Value("${dbtower.pool.evict-after-minutes:30}") long evictAfterMinutes,
            @Value("${dbtower.snapshot.interval-ms:60000}") long snapshotIntervalMs) {
        this.vaultCredentials = vaultCredentials;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxPoolSize = Math.max(1, maxPoolSize);
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.idleTimeoutMs = guardedIdleTimeout(idleTimeoutMs, snapshotIntervalMs);
        this.maxLifetimeMs = maxLifetimeMs;
        this.evictAfterMinutes = evictAfterMinutes;
    }

    /**
     * 하한 가드 — idleTimeout이 수집 주기보다 짧으면 활발한 인스턴스도 틱마다 물리 재연결을 하게 된다
     * (풀의 존재 이유가 사라짐). 수집 주기 + 30초 여유를 하한으로 강제한다.
     */
    static long guardedIdleTimeout(long configuredMs, long snapshotIntervalMs) {
        long floor = snapshotIntervalMs + 30_000;
        return Math.max(configuredMs, floor);
    }

    /**
     * 인스턴스당 커넥션 상한 — MongoDB 클라이언트 캐시도 이 값을 쓴다.
     * 드라이버 기본값(100)을 그대로 두면 "관제 도구가 대상 커넥션 슬롯을 많이 점유하면 안 된다"는
     * 이 클래스의 원칙이 한 기종에서만 깨진다. 정책의 단일 출처를 여기로 둔다.
     */
    public int maxPoolSize() {
        return maxPoolSize;
    }

    /** 유휴 커넥션 회수 시각(하한 가드 적용 후) — 기종별 클라이언트가 같은 값을 쓰게 공개한다. */
    public long idleTimeoutMs() {
        return idleTimeoutMs;
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public Connection getConnection(DatabaseInstance instance, String jdbcUrl) throws SQLException {
        return getConnection(instance, jdbcUrl, JdbcConnectOptions.DEFAULT);
    }

    public Connection getConnection(DatabaseInstance instance, String jdbcUrl, JdbcConnectOptions options) throws SQLException {
        VaultCredentials.Creds creds = credsFor(instance);
        // 등록 전 접속 검증(id 없음)은 풀을 만들지 않고 1회성 연결로 처리한다
        if (instance.getId() == null) {
            return new BoundedLoginDataSource(jdbcUrl, creds.username(), creds.password(), options).getConnection();
        }
        lastUsedMs.put(instance.getId(), System.currentTimeMillis());
        rotateIfCredentialsChanged(instance.getId(), creds);
        HikariDataSource ds = pools.computeIfAbsent(instance.getId(), id -> newPool(instance, jdbcUrl, creds, options));
        return ds.getConnection();
    }

    /** 접속에 쓸 자격증명 — vault: 접두면 동적 발급, 아니면 등록 값 그대로. */
    private VaultCredentials.Creds credsFor(DatabaseInstance instance) {
        return vaultCredentials.applies(instance)
                ? vaultCredentials.resolve(instance)
                : new VaultCredentials.Creds(instance.getUsername(), instance.getPassword());
    }

    /**
     * 동적 자격증명 회전 감지 — 풀이 이전 계정으로 만들어져 있으면 통째로 갈아끼운다(upsert의
     * 접속 정보 변경과 같은 정리 원칙). 옛 커넥션은 close가 회수하고, 만료 계정은 DB가 소멸시킨다.
     */
    private void rotateIfCredentialsChanged(Long id, VaultCredentials.Creds creds) {
        HikariDataSource existing = pools.get(id);
        if (existing != null && !creds.username().equals(existing.getUsername())) {
            log.info("동적 자격증명 회전 감지 — pool={} 재생성(new user={})", existing.getPoolName(), creds.username());
            close(id);
        }
    }

    /**
     * JdbcTemplate이 쓸 DataSource를 준다 — getConnection과 같은 자원 정책을 그대로 따른다.
     * id!=null이면 인스턴스별 HikariCP 풀(computeIfAbsent로 재사용), id==null(등록 검증)이면
     * 풀 없는 1회용 DataSource — 매 getConnection마다 새 물리 커넥션을 열고 닫아, 기존 DriverManager 1회성 연결과 동작이 같다.
     */
    public DataSource getDataSource(DatabaseInstance instance, String jdbcUrl) {
        return getDataSource(instance, jdbcUrl, JdbcConnectOptions.DEFAULT);
    }

    public DataSource getDataSource(DatabaseInstance instance, String jdbcUrl, JdbcConnectOptions options) {
        VaultCredentials.Creds creds = credsFor(instance);
        if (instance.getId() == null) {
            return new BoundedLoginDataSource(jdbcUrl, creds.username(), creds.password(), options);
        }
        lastUsedMs.put(instance.getId(), System.currentTimeMillis());
        rotateIfCredentialsChanged(instance.getId(), creds);
        return pools.computeIfAbsent(instance.getId(), id -> newPool(instance, jdbcUrl, creds, options));
    }

    /**
     * 풀을 만들 때 첫 커넥션을 붙잡지 않는다(initializationFailTimeout=-1). 풀 생성은 computeIfAbsent 안에서 일어나는데, Hikari 기본값은
     * 생성자에서 첫 연결을 동기로 시도한다 — 연결만 받고 말이 없는 대상에서 그 시도가 매달리자 같은 인스턴스를 부르던 폴러와 삭제 API가
     * 맵 락 뒤에 줄을 섰다(VERIFICATION 134절). 늦춰 두면 연결은 풀의 추가 스레드가 맡고, 호출자는 connectionTimeout만 기다린다.
     */
    private static void startLazily(HikariConfig config) {
        config.setInitializationFailTimeout(-1);
    }

    private HikariDataSource newPool(DatabaseInstance instance, String jdbcUrl, VaultCredentials.Creds creds,
                                     JdbcConnectOptions options) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(new BoundedLoginDataSource(jdbcUrl, creds.username(), creds.password(), options));
        startLazily(config);
        config.setUsername(creds.username());
        config.setPassword(creds.password());
        config.setMaximumPoolSize(maxPoolSize);
        // 온디맨드: 유휴 하한 0 — 안 쓰는 대상에 커넥션을 상시 꽂아두지 않는다.
        // 활발한 대상은 idleTimeout(수집 주기 하한 가드) 안에서 커넥션이 따뜻하게 유지된다.
        config.setMinimumIdle(0);
        config.setIdleTimeout(idleTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setPoolName("dbtower-" + instance.getName());
        return new HikariDataSource(config);
    }

    /**
     * 장기 미사용 풀 정리(LRU) — 유휴 커넥션은 idleTimeout이 이미 0으로 말리지만, 풀 객체 자체
     * (하우스키핑 스레드·메모리)는 남는다. evict-after-minutes 동안 안 쓰인 풀을 통째로 닫는다.
     * 활발한 인스턴스는 수집이 매 분 lastUsed를 갱신하므로 대상이 되지 않고, 격리·삭제 대기·저빈도
     * 대상만 잡힌다. 닫는 순간 그 풀을 막 집어간 스레드가 있으면 그 1회 조회는 실패할 수 있는데
     * (SQLException → 백오프 → 다음 틱 새 풀로 회복), 30분 미사용 풀을 동시에 쓰는 경우 자체가
     * 모순이라 락 비용 대신 그 드문 실패를 감수한다.
     */
    @Scheduled(fixedDelayString = "${dbtower.pool.evict-sweep-ms:300000}")
    public void evictIdlePools() {
        if (evictAfterMinutes <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - evictAfterMinutes * 60_000;
        evictIdleConsolePools(cutoff);
        List<Long> stale = new ArrayList<>();
        lastUsedMs.forEach((id, used) -> {
            if (used < cutoff && pools.containsKey(id)) {
                stale.add(id);
            }
        });
        for (Long id : stale) {
            HikariDataSource ds = pools.remove(id);
            lastUsedMs.remove(id);
            if (ds != null) {
                ds.close();
                log.info("유휴 풀 정리 — pool={} ({}분 미사용, 다음 사용 시 재생성)", ds.getPoolName(), evictAfterMinutes);
            }
        }
    }

    /**
     * 콘솔 계정 풀 — 모니터 풀과 같은 자원 정책(온디맨드·유휴 회수·수명)을 따르되 상한만 작다.
     * 등록 전 인스턴스(id 없음)에는 만들지 않는다: 콘솔 계정은 등록된 대상에만 붙는다.
     */
    public DataSource getConsoleDataSource(DatabaseInstance instance, String jdbcUrl,
                                           CredentialPurpose purpose, ConsoleCredential credential) {
        return getConsoleDataSource(instance, jdbcUrl, purpose, credential, JdbcConnectOptions.DEFAULT);
    }

    public DataSource getConsoleDataSource(DatabaseInstance instance, String jdbcUrl,
                                           CredentialPurpose purpose, ConsoleCredential credential,
                                           JdbcConnectOptions options) {
        if (instance.getId() == null) {
            throw new IllegalArgumentException("콘솔 풀은 등록된 인스턴스에만 만든다");
        }
        String key = instance.getId() + ":" + purpose;
        consoleLastUsedMs.put(key, System.currentTimeMillis());
        HikariDataSource existing = consolePools.get(key);
        // 계정이나 비밀번호가 바뀌었으면 옛 풀을 버린다 — 모니터 풀의 동적 자격증명 회전과 같은 원칙
        if (existing != null && (!Objects.equals(existing.getUsername(), credential.username())
                || !Objects.equals(existing.getPassword(), credential.password()))) {
            consolePools.remove(key, existing);
            existing.close();
        }
        return consolePools.computeIfAbsent(key, k -> newConsolePool(instance, jdbcUrl, purpose, credential, options));
    }

    private HikariDataSource newConsolePool(DatabaseInstance instance, String jdbcUrl,
                                            CredentialPurpose purpose, ConsoleCredential credential,
                                            JdbcConnectOptions options) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(new BoundedLoginDataSource(jdbcUrl, credential.username(), credential.password(), options));
        startLazily(config);
        config.setUsername(credential.username());
        config.setPassword(credential.password());
        config.setMaximumPoolSize(CONSOLE_POOL_MAX);
        config.setMinimumIdle(0);
        config.setIdleTimeout(idleTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setPoolName("dbtower-" + instance.getName() + "-" + purpose.name().toLowerCase());
        return new HikariDataSource(config);
    }

    private void evictIdleConsolePools(long cutoff) {
        consoleLastUsedMs.forEach((key, used) -> {
            if (used < cutoff) {
                HikariDataSource ds = consolePools.remove(key);
                consoleLastUsedMs.remove(key);
                if (ds != null) {
                    ds.close();
                    log.info("유휴 콘솔 풀 정리 — pool={}", ds.getPoolName());
                }
            }
        });
    }

    /** 인스턴스 삭제 시 풀도 정리한다 — 안 하면 죽은 대상의 커넥션이 남는다 */
    public void close(Long instanceId) {
        HikariDataSource ds = pools.remove(instanceId);
        lastUsedMs.remove(instanceId);
        vaultCredentials.evict(instanceId);
        String prefix = instanceId + ":";
        consolePools.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(prefix)) {
                return false;
            }
            e.getValue().close();
            return true;
        });
        consoleLastUsedMs.keySet().removeIf(k -> k.startsWith(prefix));
        if (ds != null) {
            ds.close();
        }
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
        lastUsedMs.clear();
        consolePools.values().forEach(HikariDataSource::close);
        consolePools.clear();
        consoleLastUsedMs.clear();
    }

    private static final Executor DIRECT = Runnable::run;

    /**
     * 기종이 준 속성으로 붙고, 기종이 원하면 로그인이 끝난 커넥션의 네트워크 읽기 제한을 되돌리는 DataSource.
     * SQL Server 드라이버는 loginTimeout을 prelogin 소켓 읽기에 걸지 않았고(mssql-jdbc #1529), Oracle thin URL에는 아무 제한이 없어
     * 연결만 받고 말이 없는 대상에서 둘 다 무기한 매달렸다(134절). 로그인 뒤까지 짧은 제한을 남기면 서버 사이드 BACKUP·RESTORE·Data Pump가
     * 끊기므로, 그 기종들은 로그인이 끝나면 예전처럼 제한을 푼다.
     */
    private static final class BoundedLoginDataSource implements DataSource {

        private final String url;
        private final String username;
        private final String password;
        private final JdbcConnectOptions options;

        BoundedLoginDataSource(String url, String username, String password, JdbcConnectOptions options) {
            this.url = url;
            this.username = username;
            this.password = password;
            this.options = options;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return getConnection(username, password);
        }

        @Override
        public Connection getConnection(String user, String pass) throws SQLException {
            Properties props = new Properties();
            props.putAll(options.loginProperties());
            if (user != null) {
                props.setProperty("user", user);
            }
            if (pass != null) {
                props.setProperty("password", pass);
            }
            Connection connection = DriverManager.getConnection(url, props);
            if (options.networkTimeoutAfterLoginMs() != null) {
                try {
                    connection.setNetworkTimeout(DIRECT, options.networkTimeoutAfterLoginMs());
                } catch (SQLException | RuntimeException e) {
                    connection.close();
                    throw e;
                }
            }
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("래핑한 대상이 아니다: " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
