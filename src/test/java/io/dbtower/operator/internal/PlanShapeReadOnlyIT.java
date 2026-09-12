package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 플랜 플립 5기종 실태 조사 (테마 A, 159절) — queryStats가 주는 식별자를 그대로 planShapeForDigest에 넣어
 * 실제로 shape가 나오는지 본다. 회귀 감지(RegressionDetector)가 하는 것과 같은 순서다.
 *
 * 읽기 전용이며 대상 DB를 바꾸지 않는다. 비밀번호는 환경변수로만 받는다.
 * 실행: DBTOWER_PLANSHAPE_IT=1 DBTOWER_SCHEMA_IT_PASSWORD=... ./gradlew test --tests '*PlanShapeReadOnlyIT'
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_PLANSHAPE_IT", matches = "1")
class PlanShapeReadOnlyIT {

    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    @AfterEach
    void close() {
        pools.closeAll();
    }

    private DatabaseInstance instance(long id, DbmsType type, int port, String database) {
        String password = System.getenv("DBTOWER_SCHEMA_IT_PASSWORD");
        assertThat(password).as("DBTOWER_SCHEMA_IT_PASSWORD 주입 여부").isNotBlank();
        var instance = new DatabaseInstance("planshape-" + id, type, "127.0.0.1", port,
                database, "dbtower_monitor", password);
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    /** 회귀 감지와 같은 순서 — 상위 쿼리의 식별자를 그대로 넣어 shape를 받아 본다 */
    private void probe(String label, DbmsOperator operator) {
        List<QueryStat> stats = operator.queryStats(5);
        int got = 0;
        for (QueryStat q : stats) {
            Optional<String> shape = operator.planShapeForDigest(q.queryId(), q.queryText());
            String text = q.queryText() == null ? "" : q.queryText().replaceAll("\\s+", " ");
            System.out.printf("PLANSHAPE %-11s id=%-42s shape=%s | %s%n", label, q.queryId(),
                    shape.map(s -> s.length() > 60 ? s.substring(0, 60) + "..." : s).orElse("(없음)"),
                    text.substring(0, Math.min(60, text.length())));
            if (shape.isPresent()) {
                got++;
            }
        }
        System.out.printf("PLANSHAPE %-11s 상위 %d개 중 shape %d개%n", label, stats.size(), got);
    }

    @Test
    void postgres() {
        probe("POSTGRESQL", new PostgresOperator(instance(9701, DbmsType.POSTGRESQL, 15432, "sample"), pools, null));
    }

    @Test
    void mysql() {
        probe("MYSQL", new MySqlOperator(instance(9702, DbmsType.MYSQL, 13306, "sample"), pools, null, null));
    }

    @Test
    void oracle() {
        probe("ORACLE", new OracleOperator(instance(9704, DbmsType.ORACLE, 11521, "FREEPDB1"), pools, null, "SAMPLE"));
    }

    @Test
    void sqlServer() {
        probe("MSSQL", new MsSqlOperator(instance(9705, DbmsType.MSSQL,
                Integer.parseInt(System.getenv().getOrDefault("DBTOWER_MSSQL_PORT", "11433")), "sample"), pools, null));
    }

    @Test
    void mongo() {
        var cache = new MongoClientCache(pools);
        try {
            probe("MONGODB", new MongoOperator(instance(9703, DbmsType.MONGODB, 17017, "sample"), cache, null, null));
        } finally {
            cache.closeAll();
        }
    }
}
