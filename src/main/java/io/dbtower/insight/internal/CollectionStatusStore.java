package io.dbtower.insight.internal;

import io.dbtower.insight.CollectionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 스냅샷 수집 결과를 메타 DB에 남긴다(V47, #72).
 *
 * <p>쓰기는 갱신 후 없으면 삽입이다(JdbcCooldownStore와 같은 이유 — H2·PostgreSQL의 upsert 문법이 갈린다).
 * 수집은 ShedLock으로 한 노드만, 한 틱에 인스턴스마다 워커 하나만 돌아 같은 행을 두 곳에서 동시에 쓰지 않는다.</p>
 */
@Repository
public class CollectionStatusStore {

    private final JdbcTemplate jdbc;

    public CollectionStatusStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordSuccess(long instanceId, LocalDateTime at) {
        Timestamp ts = Timestamp.valueOf(at);
        if (jdbc.update("UPDATE collection_status SET last_success_at = ?, consecutive_failures = 0, failure_stage = NULL"
                + " WHERE instance_id = ?", ts, instanceId) == 0) {
            jdbc.update("INSERT INTO collection_status (instance_id, last_success_at, consecutive_failures) VALUES (?, ?, 0)",
                    instanceId, ts);
        }
    }

    public void recordFailure(long instanceId, LocalDateTime at, String stage) {
        Timestamp ts = Timestamp.valueOf(at);
        if (jdbc.update("UPDATE collection_status SET last_failure_at = ?, consecutive_failures = consecutive_failures + 1,"
                + " failure_stage = ? WHERE instance_id = ?", ts, stage, instanceId) == 0) {
            jdbc.update("INSERT INTO collection_status (instance_id, last_failure_at, consecutive_failures, failure_stage)"
                    + " VALUES (?, ?, 1, ?)", instanceId, ts, stage);
        }
    }

    public Optional<CollectionStatus> find(long instanceId) {
        List<CollectionStatus> rows = jdbc.query(
                "SELECT instance_id, last_success_at, last_failure_at, consecutive_failures, failure_stage"
                        + " FROM collection_status WHERE instance_id = ?",
                (rs, n) -> new CollectionStatus(rs.getLong(1), toTime(rs.getTimestamp(2)), toTime(rs.getTimestamp(3)),
                        rs.getInt(4), rs.getString(5)),
                instanceId);
        return rows.stream().findFirst();
    }

    public void evict(long instanceId) {
        jdbc.update("DELETE FROM collection_status WHERE instance_id = ?", instanceId);
    }

    private static LocalDateTime toTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
