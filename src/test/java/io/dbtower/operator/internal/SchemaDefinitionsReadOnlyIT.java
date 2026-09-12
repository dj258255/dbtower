package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.model.SchemaDefinitions;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DBTOWER_SCHEMA_READONLY_IT", matches = "1")
class SchemaDefinitionsReadOnlyIT {
    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    @AfterEach
    void close() {
        pools.closeAll();
    }

    private DatabaseInstance instance(long id, DbmsType type, int port, String database) {
        String password = System.getenv("DBTOWER_SCHEMA_IT_PASSWORD");
        assertThat(password).as("DBTOWER_SCHEMA_IT_PASSWORD 주입 여부").isNotBlank();
        var instance = new DatabaseInstance("schema-readonly-" + id, type, "127.0.0.1", port,
                database, "dbtower_monitor", password);
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    @Test
    void postgres() {
        inspect(new PostgresOperator(instance(9601, DbmsType.POSTGRESQL, 15432, "sample"), pools, null), true);
    }

    @Test
    void mysql() {
        var schema = new MySqlOperator(instance(9602, DbmsType.MYSQL, 13306, "sample"), pools, null, null).describeSchema();
        assertThat(schema.tables()).isNotEmpty();
        assertThat(schema.tables()).allSatisfy(t -> {
            assertThat(t.checks().status()).isEqualTo(SchemaDefinitions.Status.AVAILABLE);
            assertThat(t.triggers().status()).isEqualTo(SchemaDefinitions.Status.UNAVAILABLE);
        });
        System.out.printf("SCHEMA MYSQL tables=%d CHECK=AVAILABLE trigger=UNAVAILABLE (TRIGGER privilege gate)%n", schema.tables().size());
    }

    @Test
    void oracle() {
        inspect(new OracleOperator(instance(9604, DbmsType.ORACLE, 11521, "FREEPDB1"), pools, null, "SAMPLE"), true);
    }

    @Test
    void sqlServerCompatible() {
        inspect(new MsSqlOperator(instance(9605, DbmsType.MSSQL,
                Integer.parseInt(System.getenv().getOrDefault("DBTOWER_MSSQL_PORT", "11433")), "sample"), pools, null), false);
    }

    @Test
    void mongo() {
        var cache = new MongoClientCache(pools);
        try {
            var schema = new MongoOperator(instance(9603, DbmsType.MONGODB, 17017, "sample"), cache, null, null).describeSchema();
            assertThat(schema.tables()).isNotEmpty();
            assertThat(schema.tables()).allSatisfy(t -> {
                assertThat(t.checks().status()).isEqualTo(SchemaDefinitions.Status.UNSUPPORTED);
                assertThat(t.triggers().status()).isEqualTo(SchemaDefinitions.Status.UNSUPPORTED);
            });
            System.out.printf("SCHEMA MONGODB tables=%d CHECK=UNSUPPORTED trigger=UNSUPPORTED%n", schema.tables().size());
        } finally {
            cache.closeAll();
        }
    }

    private void inspect(DbmsOperator operator, boolean requireAvailable) {
        var schema = operator.describeSchema();
        assertThat(schema.tables()).isNotEmpty();
        if (requireAvailable) {
            assertThat(schema.tables()).allSatisfy(t -> {
                assertThat(t.checks().status()).as(t.name() + " CHECK: " + t.checks().note())
                        .isEqualTo(SchemaDefinitions.Status.AVAILABLE);
                assertThat(t.triggers().status()).as(t.name() + " trigger: " + t.triggers().note())
                        .isEqualTo(SchemaDefinitions.Status.AVAILABLE);
            });
        }
        System.out.printf("SCHEMA %s tables=%d checks=%d triggers=%d checkStates=%s triggerStates=%s%n",
                schema.type(), schema.tables().size(),
                schema.tables().stream().mapToInt(t -> t.checks().definitions().size()).sum(),
                schema.tables().stream().mapToInt(t -> t.triggers().definitions().size()).sum(),
                schema.tables().stream().map(t -> t.checks().status()).distinct().toList(),
                schema.tables().stream().map(t -> t.triggers().status()).distinct().toList());
    }
}
