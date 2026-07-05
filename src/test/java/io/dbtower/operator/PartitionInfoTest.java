package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파티션 조회 (D5) — UNSUPPORTED 정직성 규약과 MongoDB 미지원 분기를 커넥션 없이 못 박는다.
 * 관계형 4기종(MySQL/PostgreSQL/Oracle/SQL Server)에서 실제 카탈로그가 돌려주는 값은
 * 직접 만든 파티션 테이블로 라이브 검증한다(빈 결과·경계값·행수).
 */
class PartitionInfoTest {

    @Test
    void unsupported_안내행은_method가_UNSUPPORTED이고_사유를_담는다() {
        PartitionInfo u = PartitionInfo.unsupported("MongoDB 파티션 개념 없음");
        assertEquals(PartitionInfo.UNSUPPORTED, u.partitionMethod());
        assertEquals("MongoDB 파티션 개념 없음", u.boundary());
        assertNull(u.tableName(), "미지원이면 테이블명은 null — 실제 파티션인 척 위장 금지");
        assertNull(u.partitionName());
        assertNull(u.rowCount(), "미지원이면 행수는 null — 0으로 위장 금지");
        assertNull(u.sizeBytes());
    }

    @Test
    void mongo는_파티션_개념이_없어_UNSUPPORTED로_정직하게_보고한다() {
        // MongoDB는 관계형 파티셔닝이 없다 — 커넥션을 열지 않고 즉시 미지원 안내 행을 낸다
        DatabaseInstance mongo = new DatabaseInstance(
                "mg", DbmsType.MONGODB, "127.0.0.1", 27017, "app", "admin", "pw");
        List<PartitionInfo> rows = new MongoOperator(mongo, null, null).partitions(50);
        assertEquals(1, rows.size());
        assertEquals(PartitionInfo.UNSUPPORTED, rows.get(0).partitionMethod());
        assertTrue(rows.get(0).boundary().contains("샤딩"), rows.get(0).boundary());
        assertNull(rows.get(0).rowCount());
    }
}
