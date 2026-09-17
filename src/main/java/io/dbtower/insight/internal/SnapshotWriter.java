package io.dbtower.insight.internal;

import io.dbtower.insight.QuerySnapshot;
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

    /** query_snapshot.query_text 열 길이(V1·V18의 VARCHAR(4000), QuerySnapshot @Column) */
    static final int QUERY_TEXT_MAX = 4000;

    private final JdbcTemplate jdbcTemplate;

    public SnapshotWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveBatch(List<QuerySnapshot> rows) {
        jdbcTemplate.batchUpdate(INSERT_SQL, rows, rows.size(), (ps, s) -> {
            ps.setLong(1, s.getInstanceId());
            ps.setTimestamp(2, Timestamp.valueOf(s.getCapturedAt()));
            ps.setString(3, s.getQueryId());
            ps.setString(4, fitQueryText(s.getQueryText()));
            ps.setLong(5, s.getCalls());
            ps.setDouble(6, s.getTotalTimeMs());
            ps.setLong(7, s.getRowsExamined());
        });
    }

    /**
     * 열 길이를 넘는 문장을 자른다(#70). 배치 INSERT는 한 행이 길이 초과로 실패하면 그 틱의 모든 행을 잃는다 —
     * id를 수십 개 OR로 이은 문장 하나 때문에 그 대상의 비교·그래프·회귀 감지가 통째로 멈췄다.
     * 쿼리는 query_id로 식별하므로 문장은 표시용이고, 원문은 대상의 통계 뷰에 남는다.
     * 서로게이트 쌍(이모지·일부 한자)의 반쪽을 남기지 않게 한 글자 앞에서 끊는다
     */
    static String fitQueryText(String text) {
        if (text == null || text.length() <= QUERY_TEXT_MAX) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(QUERY_TEXT_MAX - 1)) ? QUERY_TEXT_MAX - 1 : QUERY_TEXT_MAX;
        return text.substring(0, end);
    }
}
