package io.dbtower.insight.internal;

import io.dbtower.insight.internal.LiveSessionHub.LiveFrame;
import io.dbtower.insight.internal.LiveSessionHub.Summary;
import io.dbtower.operator.model.SessionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메타 DB 프레임 교환(VERIFICATION 144절, V41)을 실제 PostgreSQL에서 — upsert·RETURNING·UNLOGGED는 테스트 기본 H2가 흉내 내지 못한다.
 *
 * <p>운영 스키마를 건드리지 않게 일회용 스키마를 만들고, 그 안에서 V41 마이그레이션 파일을 그대로 실행한 뒤 저장소 SQL을 돌린다.
 * 그래서 이 테스트는 저장소 코드와 마이그레이션 문장을 함께 검증한다. 한 커넥션(SingleConnectionDataSource)이라 search_path가 유지된다.
 * docker compose의 메타 DB가 필요하다: {@code DBTOWER_META_IT=1 ./gradlew test --tests '*JdbcLiveFrameStoreIT'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_META_IT", matches = "1")
class JdbcLiveFrameStoreIT {

    private static final long ID = 9_870_001L;

    private final String schema = "it_live_frame_" + System.nanoTime();
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private LiveFrameStore store;

    @BeforeEach
    void migrate() throws Exception {
        ds = new SingleConnectionDataSource(
                env("DBTOWER_DB_URL", "jdbc:postgresql://127.0.0.1:15432/dbtower"),
                env("DBTOWER_DB_USERNAME", "postgres"),
                env("DBTOWER_DB_PASSWORD", "dbtower1234"), true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("SET search_path TO " + schema);
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/V41__live_frame.sql")) {
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        store = new JdbcLiveFrameStore(jdbc);
    }

    @AfterEach
    void drop() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        ds.destroy();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Test
    void 올릴_때마다_seq가_하나씩_오르고_읽으면_세션까지_같은_프레임이_돌아온다() {
        List<SessionInfo> sessions = List.of(new SessionInfo(42, "app", "active", "relation", 7L, "select count(*) from orders", 1234.5));
        LiveFrame frame = new LiveFrame(ID, 999, 1_700_000_000_000L, 2000, 3.4, "OK", null, Summary.of(sessions), sessions);

        long first = store.publish(frame);
        long second = store.publish(frame);
        LiveFrameStore.Stored latest = store.latest(ID).orElseThrow();

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(latest.frame().seq()).isEqualTo(2);           // 프레임 안의 999가 아니라 DB가 매긴 번호
        assertThat(latest.frame().sessions()).isEqualTo(sessions);
        assertThat(latest.frame().summary().blocked()).isEqualTo(1);
        assertThat(latest.ageMs()).isBetween(0L, 5_000L);
        assertThat(jdbc.queryForObject("SELECT c.relpersistence FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relname = 'live_frame' AND n.nspname = ?", String.class, schema)).isEqualTo("u");   // UNLOGGED
    }

    @Test
    void 올린_적_없는_대상은_비어_있다() {
        assertThat(store.latest(ID)).isEmpty();
    }
}
