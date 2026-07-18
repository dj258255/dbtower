package io.dbtower.insight;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 대기 이벤트 이력 조회 (운영 병목 아크 B4 재료) — wait_event_snapshot(V25)을 시간창으로 집계한다.
 * 인시던트 리포트가 "그 구간에 무엇을 기다렸나"를 붙일 때 쓴다. 잡(WaitEventSnapshotJob)이 쓰기,
 * 이 서비스가 읽기 — 공개 API라 다른 모듈(alert의 IncidentReportService)이 참조할 수 있다.
 */
@Service
public class WaitEventHistoryService {

    private final JdbcTemplate jdbc;

    public WaitEventHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 창 안 대기 이벤트 상위 — 이벤트별 누적 대기 횟수·시간 합. */
    public record WaitPoint(String category, String event, long totalCount, double totalMs) {
    }

    /** [from, to] 안의 스냅샷을 이벤트별로 합산해 대기 횟수 내림차순 topN. 없으면 빈 목록. */
    public List<WaitPoint> inWindow(long instanceId, LocalDateTime from, LocalDateTime to, int topN) {
        return jdbc.query("""
                SELECT category, event_name,
                       sum(wait_count) AS total_count, sum(total_ms) AS total_ms
                FROM wait_event_snapshot
                WHERE instance_id = ? AND captured_at BETWEEN ? AND ?
                GROUP BY category, event_name
                ORDER BY total_count DESC
                LIMIT ?
                """, (rs, i) -> new WaitPoint(
                rs.getString("category"), rs.getString("event_name"),
                rs.getLong("total_count"), rs.getDouble("total_ms")),
                instanceId, Timestamp.valueOf(from), Timestamp.valueOf(to), topN);
    }
}
