package io.dbtower.insight.internal.job;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파티션 수명주기 판정 (V18) — DROP은 "그 달 전체가 보존 기한을 지난" 파티션만, 보수적으로.
 * 이름 규약(query_snapshot_yYYYYmMM)은 생성 DDL과 판정 파싱이 한 쌍이라 함께 고정한다.
 */
class SnapshotRetentionPartitionTest {

    @Test
    void 월_전체가_기한을_지난_파티션만_DROP_대상이다() {
        LocalDateTime cutoff = LocalDateTime.parse("2026-07-09T00:00:00"); // 보존 7일, 7/16 기준
        // 6월 파티션: 상한 7/1 <= cutoff → 통째로 기한 경과
        assertTrue(SnapshotRetentionJob.droppable("query_snapshot_y2026m06", cutoff));
        // 7월 파티션: 상한 8/1 > cutoff → 기한이 걸쳐 있음(내부 잔여는 DELETE 몫)
        assertFalse(SnapshotRetentionJob.droppable("query_snapshot_y2026m07", cutoff));
    }

    @Test
    void 상한이_정확히_cutoff와_같으면_DROP_가능하다() {
        // 6월 상한(exclusive) 7/1 == cutoff 7/1 → 6월의 모든 행이 cutoff 이전
        assertTrue(SnapshotRetentionJob.droppable("query_snapshot_y2026m06",
                LocalDateTime.parse("2026-07-01T00:00:00")));
    }

    @Test
    void 이름_규약을_안_따르는_자식은_건드리지_않는다() {
        LocalDateTime cutoff = LocalDateTime.parse("2026-07-09T00:00:00");
        // DEFAULT 파티션·수동 생성 테이블 — DROP은 보수적으로
        assertFalse(SnapshotRetentionJob.droppable("query_snapshot_pdefault", cutoff));
        assertFalse(SnapshotRetentionJob.droppable("query_snapshot_backup", cutoff));
    }

    @Test
    void 생성_DDL은_판정과_같은_이름_규약을_쓴다() {
        String sql = SnapshotRetentionJob.createPartitionSql(YearMonth.of(2026, 8));
        assertTrue(sql.contains("query_snapshot_y2026m08"));
        assertTrue(sql.contains("FROM ('2026-08-01') TO ('2026-09-01')"));
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS"), "선생성은 멱등이어야 한다");
        // 생성한 이름이 나중에 DROP 판정 가능해야 규약이 한 쌍이다
        assertTrue(SnapshotRetentionJob.droppable("query_snapshot_y2026m08",
                LocalDateTime.parse("2026-09-01T00:00:00")));
    }
}
