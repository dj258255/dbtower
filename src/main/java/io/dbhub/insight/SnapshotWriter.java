package io.dbhub.insight;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

/**
 * 스냅샷 저장 전용 컴포넌트.
 *
 * 개선 아크 2: JPA saveAll은 행마다 INSERT를 따로 보낸다(영속성 컨텍스트 관리 비용 포함).
 * 수집 데이터는 불변 로그라 영속성 컨텍스트가 필요 없으므로 JDBC batchUpdate로 교체.
 * PG에서 진짜 배치가 되려면 URL에 reWriteBatchedInserts=true가 필요하다 —
 * 이 옵션이 여러 INSERT를 multi-values 한 문장으로 다시 써준다.
 */
@Component
public class SnapshotWriter {

    private static final String INSERT_SQL = """
            INSERT INTO query_snapshot
                (instance_id, captured_at, query_id, query_text, calls, total_time_ms, rows_examined)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public SnapshotWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveBatch(List<QuerySnapshot> rows) {
        jdbcTemplate.batchUpdate(INSERT_SQL, rows, rows.size(), (ps, s) -> {
            ps.setLong(1, s.getInstanceId());
            ps.setTimestamp(2, Timestamp.valueOf(s.getCapturedAt()));
            ps.setString(3, s.getQueryId());
            ps.setString(4, s.getQueryText());
            ps.setLong(5, s.getCalls());
            ps.setDouble(6, s.getTotalTimeMs());
            ps.setLong(7, s.getRowsExamined());
        });
    }
}
