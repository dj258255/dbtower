package io.dbtower;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파티션 수명주기 공용 판정 (V18 query_snapshot → V19 health_sample 일반화) —
 * DROP은 "그 달 전체가 보존 기한을 지난" 파티션만, 보수적으로. 이름 규약(<table>_yYYYYmMM)은
 * 생성 DDL과 판정 파싱이 한 쌍이라 함께 고정한다.
 */
class PartitionLifecycleTest {

    @Test
    void 월_전체가_기한을_지난_파티션만_DROP_대상이다() {
        LocalDateTime cutoff = LocalDateTime.parse("2026-07-09T00:00:00"); // 보존 7일, 7/16 기준
        // 6월 파티션: 상한 7/1 <= cutoff → 통째로 기한 경과
        assertTrue(PartitionLifecycle.droppable("query_snapshot", "query_snapshot_y2026m06", cutoff));
        // 7월 파티션: 상한 8/1 > cutoff → 기한이 걸쳐 있음(내부 잔여는 DELETE 몫)
        assertFalse(PartitionLifecycle.droppable("query_snapshot", "query_snapshot_y2026m07", cutoff));
    }

    @Test
    void 상한이_정확히_cutoff와_같으면_DROP_가능하다() {
        assertTrue(PartitionLifecycle.droppable("health_sample", "health_sample_y2026m06",
                LocalDateTime.parse("2026-07-01T00:00:00")));
    }

    @Test
    void 이름_규약을_안_따르거나_다른_테이블의_자식은_건드리지_않는다() {
        LocalDateTime cutoff = LocalDateTime.parse("2026-07-09T00:00:00");
        // DEFAULT 파티션·수동 생성 테이블 — DROP은 보수적으로
        assertFalse(PartitionLifecycle.droppable("query_snapshot", "query_snapshot_pdefault", cutoff));
        assertFalse(PartitionLifecycle.droppable("query_snapshot", "query_snapshot_backup", cutoff));
        // 접두가 다른 테이블의 파티션 이름이면 어떤 이유로든 대상 아님
        assertFalse(PartitionLifecycle.droppable("health_sample", "query_snapshot_y2026m06", cutoff));
    }

    @Test
    void 일_파티션도_그날_전체가_기한_밖일_때만_지운다() {
        LocalDateTime cutoff = LocalDateTime.parse("2026-09-18T00:00:00");
        // 9/17 은 9/18 00:00 에 끝나므로 지울 수 있다
        assertTrue(PartitionLifecycle.droppable("ash_sample", "ash_sample_y2026m09d17", cutoff));
        // 9/18 은 9/19 00:00 에 끝난다 — 아직 cutoff 뒤라 남긴다
        assertFalse(PartitionLifecycle.droppable("ash_sample", "ash_sample_y2026m09d18", cutoff));
    }

    @Test
    void 일_파티션이_월_패턴에_걸려_영영_안_지워지지_않는다() {
        // 월 패턴(_yYYYYmMM)만 보면 뒤의 dDD 가 남아 matches() 가 실패한다.
        // 그러면 "이름 규약을 안 따르는 자식"으로 분류돼 조용히 영원히 쌓인다 — 제일 나쁜 실패다(#149)
        LocalDateTime farFuture = LocalDateTime.parse("2030-01-01T00:00:00");
        assertTrue(PartitionLifecycle.droppable("ash_sample", "ash_sample_y2026m09d17", farFuture),
                "일 파티션이 판정에서 빠지면 보존 설정이 무의미해진다");
    }

    @Test
    void V49가_실제로_만드는_자식_이름이_판정된다() {
        // 실제 PostgreSQL 에 마이그레이션 49개를 적용해 확인한 이름이다.
        // 부모를 RENAME 해도 자식은 그대로라 한때 ash_sample_part_y... 로 남았고,
        // startsWith(table + "_y") 에 걸리지 않아 보존 스윕이 영원히 지나쳤다(#149).
        LocalDateTime cutoff = LocalDateTime.parse("2026-09-26T00:00:00");
        assertTrue(PartitionLifecycle.droppable("ash_sample", "ash_sample_y2026m09d18", cutoff));
        assertTrue(PartitionLifecycle.droppable("ash_sample_tick", "ash_sample_tick_y2026m09d18", cutoff));
        // 전환기의 잘못된 이름은 판정되지 않는다 — 이 단언이 그때의 실패를 고정한다
        assertFalse(PartitionLifecycle.droppable("ash_sample", "ash_sample_part_y2026m09d18", cutoff));
        // DEFAULT 는 언제나 남긴다
        assertFalse(PartitionLifecycle.droppable("ash_sample", "ash_sample_pdefault", cutoff));
        // ash_sample 의 자식으로 ash_sample_tick_... 을 넘겨도 걸리지 않아야 한다(접두가 겹친다)
        assertFalse(PartitionLifecycle.droppable("ash_sample_tick", "ash_sample_y2026m09d18", cutoff));
    }

    @Test
    void 일_생성_DDL도_판정과_같은_이름_규약을_쓴다() {
        String sql = PartitionLifecycle.createDailyPartitionSql("ash_sample", LocalDate.of(2026, 9, 18));
        assertTrue(sql.contains("ash_sample_y2026m09d18"));
        assertTrue(sql.contains("FROM ('2026-09-18') TO ('2026-09-19')"));
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS"), "선생성은 멱등이어야 한다");
        assertTrue(PartitionLifecycle.droppable("ash_sample", "ash_sample_y2026m09d18",
                LocalDateTime.parse("2026-09-19T00:00:00")));
    }

    @Test
    void 생성_DDL은_판정과_같은_이름_규약을_쓴다() {
        String sql = PartitionLifecycle.createPartitionSql("health_sample", YearMonth.of(2026, 8));
        assertTrue(sql.contains("health_sample_y2026m08"));
        assertTrue(sql.contains("FROM ('2026-08-01') TO ('2026-09-01')"));
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS"), "선생성은 멱등이어야 한다");
        // 생성한 이름이 나중에 DROP 판정 가능해야 규약이 한 쌍이다
        assertTrue(PartitionLifecycle.droppable("health_sample", "health_sample_y2026m08",
                LocalDateTime.parse("2026-09-01T00:00:00")));
    }
}
