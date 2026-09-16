package io.dbtower.alert.internal.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 경보 쿨다운을 메타 DB에 둔다(V45). 재기동하거나 락 보유 노드가 바뀌어도 이미 알린 신호를 쿨다운 창 안에서 다시 알리지 않는다.
 *
 * <p>쓰기는 갱신 후 없으면 삽입이다. 감지기는 ShedLock으로 한 시점에 한 노드만 돌아 같은 키를 두 곳에서 동시에 쓰지 않는다 —
 * 그래서 H2·PostgreSQL 문법이 갈리는 upsert를 쓰지 않는다.</p>
 *
 * <p>접두 비교에 LIKE를 쓰지 않는다. 키에 쿼리 식별자가 들어가는데, 기종에 따라 밑줄이 섞이면 LIKE에서 와일드카드가 된다.</p>
 */
@Repository
public class JdbcCooldownStore implements CooldownStore {

    private final JdbcTemplate jdbc;

    public JdbcCooldownStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LocalDateTime lastAlerted(String key) {
        List<Timestamp> rows = jdbc.queryForList(
                "SELECT alerted_at FROM alert_cooldown WHERE cooldown_key = ?", Timestamp.class, key);
        return rows.isEmpty() ? null : rows.get(0).toLocalDateTime();
    }

    @Override
    public void record(Collection<String> keys, LocalDateTime at) {
        Timestamp ts = Timestamp.valueOf(at);
        for (String key : keys) {
            if (jdbc.update("UPDATE alert_cooldown SET alerted_at = ? WHERE cooldown_key = ?", ts, key) == 0) {
                jdbc.update("INSERT INTO alert_cooldown (cooldown_key, alerted_at) VALUES (?, ?)", key, ts);
            }
        }
    }

    @Override
    public void evictPrefix(String prefix) {
        jdbc.update("DELETE FROM alert_cooldown WHERE LEFT(cooldown_key, ?) = ?", prefix.length(), prefix);
    }

    @Override
    public void pruneBefore(String prefix, LocalDateTime cutoff) {
        jdbc.update("DELETE FROM alert_cooldown WHERE LEFT(cooldown_key, ?) = ? AND alerted_at < ?",
                prefix.length(), prefix, Timestamp.valueOf(cutoff));
    }
}
