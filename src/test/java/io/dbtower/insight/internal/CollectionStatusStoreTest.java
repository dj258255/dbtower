package io.dbtower.insight.internal;

import io.dbtower.insight.CollectionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionStatusStoreTest {

    private CollectionStatusStore store;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:collection-status-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE collection_status (
                    instance_id          BIGINT      PRIMARY KEY,
                    last_success_at      TIMESTAMP,
                    last_failure_at      TIMESTAMP,
                    consecutive_failures INT         NOT NULL DEFAULT 0,
                    failure_stage        VARCHAR(20)
                )""");
        store = new CollectionStatusStore(jdbc);
    }

    @Test
    void 기록이_없으면_비어_있다() {
        assertThat(store.find(1)).isEmpty();
    }

    @Test
    void 실패가_이어지면_연속_횟수와_마지막_단계가_남고_성공하면_0으로_돌아간다() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 17, 13, 22);
        store.recordSuccess(1, t0);
        store.recordFailure(1, t0.plusMinutes(1), CollectionStatus.STORE);
        store.recordFailure(1, t0.plusMinutes(2), CollectionStatus.TARGET);

        CollectionStatus failing = store.find(1).orElseThrow();
        assertThat(failing.failing()).isTrue();
        assertThat(failing.consecutiveFailures()).isEqualTo(2);
        assertThat(failing.stage()).isEqualTo(CollectionStatus.TARGET);
        assertThat(failing.lastSuccessAt()).isEqualTo(t0);
        assertThat(failing.lastFailureAt()).isEqualTo(t0.plusMinutes(2));

        store.recordSuccess(1, t0.plusMinutes(3));
        CollectionStatus recovered = store.find(1).orElseThrow();
        assertThat(recovered.failing()).isFalse();
        assertThat(recovered.stage()).isNull();
        assertThat(recovered.lastFailureAt()).as("마지막 실패 시각은 이력으로 남긴다").isEqualTo(t0.plusMinutes(2));
    }

    @Test
    void 처음_기록이_실패여도_행이_생긴다() {
        store.recordFailure(7, LocalDateTime.of(2026, 9, 17, 13, 54), CollectionStatus.STORE);
        assertThat(store.find(7)).get().extracting(CollectionStatus::consecutiveFailures).isEqualTo(1);
        store.evict(7);
        assertThat(store.find(7)).isEmpty();
    }
}
