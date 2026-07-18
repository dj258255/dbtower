package io.dbtower.alert.internal.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설정 드리프트 저장·조회 (운영 병목 아크 B1). JdbcTemplate 직결 — 스냅샷 잡 계열
 * (WaitEventSnapshotJob·SizeSnapshotJob)과 같은 결로, JPA 엔티티 없이 관계형 조회에 맞춘다.
 *
 * config_current_param을 '현재 전량 거울'로 두고 변경분만 반영해, 무변경 주기엔 config_snapshot
 * 한 줄만 쌓이게 한다(폭증 방지).
 */
@Repository
public class ConfigDriftDao {

    private final JdbcTemplate jdbc;

    public ConfigDriftDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 현재 거울 — 인스턴스의 마지막으로 확정된 파라미터 전량(name -> value). 없으면 빈 맵(첫 수집). */
    public Map<String, String> currentParams(long instanceId) {
        Map<String, String> map = new LinkedHashMap<>();
        jdbc.query("SELECT param_name, param_value FROM config_current_param WHERE instance_id = ? ORDER BY param_name",
                rs -> {
                    map.put(rs.getString("param_name"), rs.getString("param_value"));
                }, instanceId);
        return map;
    }

    /** 수집 흔적 1행 삽입 후 생성된 id 반환(무변경도 호출 — 해시가 곧 무변경 증거). */
    public long insertSnapshot(long instanceId, LocalDateTime capturedAt, String paramHash,
                               int changeCount, boolean baseline) {
        Timestamp ts = Timestamp.valueOf(capturedAt);
        return jdbc.queryForObject("""
                INSERT INTO config_snapshot (instance_id, captured_at, param_hash, change_count, baseline)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, instanceId, ts, paramHash, changeCount, baseline);
    }

    /** 변경 이벤트 로그 — 바뀐 항목만. */
    public void insertChanges(long snapshotId, long instanceId, LocalDateTime capturedAt,
                              List<ParamChangeRow> changes) {
        Timestamp ts = Timestamp.valueOf(capturedAt);
        jdbc.batchUpdate("""
                INSERT INTO config_param_change
                    (snapshot_id, instance_id, captured_at, param_name, old_value, new_value, change_kind)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, changes, changes.size(), (ps, c) -> {
            ps.setLong(1, snapshotId);
            ps.setLong(2, instanceId);
            ps.setTimestamp(3, ts);
            ps.setString(4, c.paramName());
            ps.setString(5, c.oldValue());
            ps.setString(6, c.newValue());
            ps.setString(7, c.kind());
        });
    }

    /** 첫 수집(기준선) — 전량을 거울에 심는다. */
    public void insertBaseline(long instanceId, Map<String, String> params) {
        List<Map.Entry<String, String>> entries = List.copyOf(params.entrySet());
        jdbc.batchUpdate("INSERT INTO config_current_param (instance_id, param_name, param_value) VALUES (?, ?, ?)",
                entries, entries.size(), (ps, e) -> {
                    ps.setLong(1, instanceId);
                    ps.setString(2, e.getKey());
                    ps.setString(3, e.getValue());
                });
    }

    /** 거울 갱신 — 추가/변경은 upsert, 삭제는 delete(변경분만 건드린다). */
    public void applyDeltas(long instanceId, List<ParamChangeRow> changes) {
        for (ParamChangeRow c : changes) {
            if ("REMOVED".equals(c.kind())) {
                jdbc.update("DELETE FROM config_current_param WHERE instance_id = ? AND param_name = ?",
                        instanceId, c.paramName());
            } else {
                jdbc.update("""
                        INSERT INTO config_current_param (instance_id, param_name, param_value)
                        VALUES (?, ?, ?)
                        ON CONFLICT (instance_id, param_name) DO UPDATE SET param_value = EXCLUDED.param_value
                        """, instanceId, c.paramName(), c.newValue());
            }
        }
    }

    /** 콘솔 타임라인 — 인스턴스의 변경 이벤트를 최신순으로(P3). */
    public List<ParamChangeRow> recentChanges(long instanceId, int limit) {
        return jdbc.query("""
                SELECT captured_at, param_name, old_value, new_value, change_kind
                FROM config_param_change WHERE instance_id = ?
                ORDER BY captured_at DESC, param_name LIMIT ?
                """, (rs, i) -> new ParamChangeRow(
                rs.getString("param_name"), rs.getString("old_value"), rs.getString("new_value"),
                rs.getString("change_kind"), rs.getTimestamp("captured_at").toLocalDateTime()),
                instanceId, limit);
    }

    /** 플랜 플립 대조(P4) — 기준 시각 ±windowHours 안의 변경 수. */
    public int changeCountAround(long instanceId, LocalDateTime center, int windowHours) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM config_param_change
                WHERE instance_id = ? AND captured_at BETWEEN ? AND ?
                """, Integer.class, instanceId,
                Timestamp.valueOf(center.minusHours(windowHours)),
                Timestamp.valueOf(center.plusHours(windowHours)));
        return n == null ? 0 : n;
    }

    /** 보존 정리 — 오래된 스냅샷(변경 로그는 FK CASCADE로 함께). 거울은 건드리지 않는다. */
    public int sweepOlderThan(LocalDateTime cutoff) {
        return jdbc.update("DELETE FROM config_snapshot WHERE captured_at < ?", Timestamp.valueOf(cutoff));
    }

    /** 변경 한 건(로그 행·타임라인 행 공용). captured_at은 타임라인 조회에서만 채워진다. */
    public record ParamChangeRow(String paramName, String oldValue, String newValue,
                                 String kind, LocalDateTime capturedAt) {
        public ParamChangeRow(String paramName, String oldValue, String newValue, String kind) {
            this(paramName, oldValue, newValue, kind, null);
        }
    }
}
