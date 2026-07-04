package io.dbtower;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 분산 락의 핵심 계약을 고정한다: "락이 이미 잡혀 있으면 두 번째 획득은 스킵된다."
 *
 * 이것이 HA(다중 인스턴스)에서 폴러가 한 노드에서만 도는 근거다 — 두 노드가 같은 이름으로
 * 동시에 락을 시도하면 한쪽만 성공하고 다른 쪽은 빈 Optional을 받아 조용히 건너뛴다.
 *
 * 실 DB(PostgreSQL) 없이 H2로 JdbcTemplateLockProvider의 실제 SQL 동작(INSERT 실패/조건부 UPDATE)을
 * 검증한다. Flyway가 테스트에서 꺼져 있어 shedlock 테이블을 이 테스트가 직접 만든다(V3와 동일 스키마).
 */
class SchedulerLockProviderTest {

    private LockProvider lockProvider;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:shedlock-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS shedlock");
        jdbc.execute("CREATE TABLE shedlock ("
                + "name VARCHAR(64) NOT NULL, lock_until TIMESTAMP NOT NULL, "
                + "locked_at TIMESTAMP NOT NULL, locked_by VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (name))");
        lockProvider = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbc)
                        .build());
    }

    // lockAtLeastFor=0 — 해제 즉시 다시 획득 가능하게 해 재획득 경로까지 검증한다
    private LockConfiguration config(String name) {
        return new LockConfiguration(Instant.now(), name, Duration.ofMinutes(2), Duration.ZERO);
    }

    @Test
    void 락이_이미_있으면_두번째_획득은_스킵된다() {
        Optional<SimpleLock> first = lockProvider.lock(config("snapshot-collect"));
        assertTrue(first.isPresent(), "첫 획득은 성공해야 한다");

        // 같은 이름 재시도 = 다른 노드가 같은 폴러를 동시에 돌리려는 상황
        Optional<SimpleLock> second = lockProvider.lock(config("snapshot-collect"));
        assertTrue(second.isEmpty(), "락 보유 중 두번째 획득은 스킵(빈 Optional)되어야 한다");

        first.get().unlock();

        Optional<SimpleLock> third = lockProvider.lock(config("snapshot-collect"));
        assertTrue(third.isPresent(), "해제 후에는 다시 획득 가능해야 한다");
        third.get().unlock();
    }

    @Test
    void 서로_다른_이름의_락은_독립적으로_획득된다() {
        // 폴러마다 고정 name이 다르므로 서로를 막지 않아야 한다(수집이 회귀 감지를 막으면 안 된다)
        Optional<SimpleLock> collect = lockProvider.lock(config("snapshot-collect"));
        Optional<SimpleLock> detect = lockProvider.lock(config("regression-detect"));
        assertTrue(collect.isPresent(), "snapshot-collect 락 획득");
        assertTrue(detect.isPresent(), "regression-detect 락은 collect와 무관하게 획득되어야 한다");
        collect.get().unlock();
        detect.get().unlock();
    }
}
