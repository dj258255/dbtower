package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.model.LatencyPercentile;
import io.dbtower.operator.model.QueryAntiPattern;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.DriverManager;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 5기종이 화면에 주는 수치의 공통 불변식을 읽기 전용으로 확인한다.
 *
 * <p>화면이 열리는지만 보면 단위 변환이나 DB 시계 의미가 틀린 값을 놓친다. 162절에서 PostgreSQL
 * 세션 경과가 -7.84ms로 찍힌 결함처럼, 값은 있어도 의미상 불가능한 결과를 실DB 경계에서 막는다.
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_VALUE_SANITY_IT", matches = "1")
class OperatorValueSanityReadOnlyIT {

    private static final Set<String> LATENCY_SOURCES = Set.of(
            LatencyPercentile.NATIVE,
            LatencyPercentile.NATIVE_WINDOWED,
            LatencyPercentile.NATIVE_HISTOGRAM,
            LatencyPercentile.COMPUTED,
            LatencyPercentile.ESTIMATED,
            LatencyPercentile.UNSUPPORTED);

    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);
    private final HistogramSnapshotStore histograms = new HistogramSnapshotStore();
    private MongoClientCache mongoClients;

    @AfterEach
    void close() {
        if (mongoClients != null) {
            mongoClients.closeAll();
        }
        pools.closeAll();
    }

    @Test
    void mysql() {
        verify("MYSQL", new MySqlOperator(instance(9801, DbmsType.MYSQL, 13306, "sample"), pools, null, histograms));
    }

    @Test
    void postgres() throws Exception {
        PostgresOperator operator = new PostgresOperator(
                instance(9802, DbmsType.POSTGRESQL, 15432, "sample"), pools, null);
        verify("POSTGRESQL", operator);
        verifyPostgresRunningQueryElapsed(operator);
    }

    @Test
    void sqlServer() {
        int port = Integer.parseInt(System.getenv().getOrDefault("DBTOWER_MSSQL_PORT", "11433"));
        verify("MSSQL", new MsSqlOperator(instance(9803, DbmsType.MSSQL, port, "sample"), pools, null));
    }

    @Test
    void oracle() {
        verify("ORACLE", new OracleOperator(instance(9804, DbmsType.ORACLE, 11521, "FREEPDB1"), pools, null, "SAMPLE"));
    }

    @Test
    void mongo() {
        mongoClients = new MongoClientCache(pools);
        verify("MONGODB", new MongoOperator(instance(9805, DbmsType.MONGODB, 17017, "sample"),
                mongoClients, null, histograms));
    }

    private DatabaseInstance instance(long id, DbmsType type, int port, String database) {
        String password = System.getenv("DBTOWER_SCHEMA_IT_PASSWORD");
        assertThat(password).as("DBTOWER_SCHEMA_IT_PASSWORD 주입 여부").isNotBlank();
        DatabaseInstance instance = new DatabaseInstance("value-sanity-" + id, type, "127.0.0.1", port,
                database, "dbtower_monitor", password);
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    private void verify(String label, DbmsOperator operator) {
        var health = operator.health();
        assertThat(health.up()).as(label + " health").isTrue();
        assertThat(health.pingMillis()).as(label + " ping ms").isGreaterThanOrEqualTo(0);

        var stats = operator.queryStats(20);
        stats.forEach(q -> {
            assertThat(q.calls()).as(label + " query calls").isGreaterThanOrEqualTo(0);
            finiteNonNegative(label + " query total ms", q.totalTimeMs());
            assertThat(q.rowsExamined()).as(label + " query rows").isGreaterThanOrEqualTo(0);
        });

        var slow = operator.slowQueries(20);
        slow.forEach(q -> {
            finiteNonNegative(label + " slow elapsed ms", q.elapsedMs());
            assertThat(q.rowsExamined()).as(label + " slow rows").isGreaterThanOrEqualTo(-1);
            assertThat(q.rowsSent()).as(label + " slow rows sent").isGreaterThanOrEqualTo(-1);
            assertThat(q.lockMs()).as(label + " slow lock ms").isGreaterThanOrEqualTo(-1);
            assertThat(Double.isFinite(q.lockMs())).as(label + " slow lock finite").isTrue();
        });

        var sessions = operator.activeSessions(50);
        sessions.forEach(s -> {
            finiteNonNegative(label + " session elapsed ms", s.elapsedMs());
            assertThat(s.state()).as(label + " session state").isNotEqualToIgnoringCase("Daemon");
            assertThat(s.user()).as(label + " session user").isNotEqualTo("event_scheduler");
        });

        var tables = operator.tableStats(20);
        tables.forEach(t -> {
            assertThat(t.rowCount()).as(label + " table rows").isGreaterThanOrEqualTo(0);
            assertThat(t.dataBytes()).as(label + " table data bytes").isGreaterThanOrEqualTo(0);
            assertThat(t.indexBytes()).as(label + " table index bytes").isGreaterThanOrEqualTo(0);
        });

        var percentiles = operator.latencyPercentiles(20);
        percentiles.forEach(p -> {
            assertThat(p.source()).as(label + " latency source").isIn(LATENCY_SOURCES);
            if (LatencyPercentile.UNSUPPORTED.equals(p.source())) {
                assertThat(p.p95Ms()).as(label + " unsupported p95").isNull();
                assertThat(p.p99Ms()).as(label + " unsupported p99").isNull();
                return;
            }
            if (p.p95Ms() != null) {
                finiteNonNegative(label + " p95 ms", p.p95Ms());
            }
            if (p.p99Ms() != null) {
                finiteNonNegative(label + " p99 ms", p.p99Ms());
            }
            if (p.p95Ms() != null && p.p99Ms() != null) {
                assertThat(p.p99Ms()).as(label + " p99 >= p95").isGreaterThanOrEqualTo(p.p95Ms());
            }
        });

        var antiPatterns = operator.queryAntiPatterns(20);
        antiPatterns.forEach(a -> {
            assertThat(a.calls()).as(label + " anti-pattern calls").isGreaterThanOrEqualTo(0);
            metric(label, a.fullScan());
            metric(label, a.diskSpill());
            metric(label, a.examinedPerRow());
        });

        double maxQueryMs = stats.stream().mapToDouble(q -> q.totalTimeMs()).max().orElse(0);
        double maxSessionMs = sessions.stream().mapToDouble(s -> s.elapsedMs()).max().orElse(0);
        double maxP99Ms = percentiles.stream().filter(p -> p.p99Ms() != null)
                .mapToDouble(p -> p.p99Ms()).max().orElse(0);
        System.out.printf("VALUE %-11s ping=%dms query=%d maxTotal=%.2fms slow=%d session=%d maxElapsed=%.2fms "
                        + "table=%d percentile=%d maxP99=%.2fms antiPattern=%d%n",
                label, health.pingMillis(), stats.size(), maxQueryMs, slow.size(), sessions.size(), maxSessionMs,
                tables.size(), percentiles.size(), maxP99Ms, antiPatterns.size());
    }

    private void metric(String label, QueryAntiPattern.Metric metric) {
        if (metric == null || metric.value() == null) {
            return;
        }
        finiteNonNegative(label + " anti-pattern " + metric.sourceName(), metric.value());
    }

    private void finiteNonNegative(String label, double value) {
        assertThat(Double.isFinite(value)).as(label + " finite").isTrue();
        assertThat(value).as(label).isGreaterThanOrEqualTo(0);
    }

    private void verifyPostgresRunningQueryElapsed(PostgresOperator operator) throws Exception {
        String password = System.getenv("DBTOWER_SCHEMA_IT_PASSWORD");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sleeper = executor.submit(() -> {
                try (var connection = DriverManager.getConnection(
                        "jdbc:postgresql://127.0.0.1:15432/sample?ApplicationName=value-sanity-probe",
                        "dbtower_monitor", password);
                     var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_sleep(2)");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            Double elapsed = null;
            while (System.nanoTime() < deadline && elapsed == null) {
                elapsed = operator.activeSessions(50).stream()
                        .filter(s -> s.query() != null && s.query().contains("pg_sleep(2)"))
                        .map(s -> s.elapsedMs())
                        .findFirst()
                        .orElse(null);
                if (elapsed == null) {
                    Thread.sleep(50);
                }
            }
            assertThat(elapsed).as("POSTGRESQL 실행 중 쿼리 포착").isNotNull();
            finiteNonNegative("POSTGRESQL running query elapsed ms", elapsed);
            assertThat(elapsed).as("POSTGRESQL 실행 중인 2초 쿼리 경과").isLessThan(2_500);
            sleeper.get(4, TimeUnit.SECONDS);
            System.out.printf("VALUE POSTGRESQL  runningQueryElapsed=%.2fms%n", elapsed);
        }
    }
}
