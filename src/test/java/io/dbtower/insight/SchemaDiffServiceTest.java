package io.dbtower.insight;

import io.dbtower.operator.ColumnSchema;
import io.dbtower.operator.IndexSchema;
import io.dbtower.operator.SchemaSnapshot;
import io.dbtower.operator.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스키마 diff 로직 검증 (B7) — 대상 DB 없이 두 SchemaSnapshot의 차이를 정확히 잡는지 증명한다.
 * "왜 저 장비만 다르지"의 실제 원인들(운영에만 있는 컬럼/인덱스, 타입 변경, 사라진 테이블)을 모두 재현한다.
 */
class SchemaDiffServiceTest {

    private final SchemaDiffService service = new SchemaDiffService();

    private static ColumnSchema col(String name, String type, boolean nullable, int ord) {
        return new ColumnSchema(name, type, nullable, ord);
    }

    @Test
    void 추가_삭제_변경을_테이블_컬럼_인덱스_수준에서_잡는다() {
        // left(베이스라인): users + orders
        TableSchema leftUsers = new TableSchema("users",
                List.of(col("id", "bigint", false, 1), col("name", "varchar(100)", true, 2)),
                List.of(new IndexSchema("pk_users", List.of("id"), true),
                        new IndexSchema("idx_name", List.of("name"), false)));
        TableSchema orders = new TableSchema("orders",
                List.of(col("id", "bigint", false, 1)), List.of());
        SchemaSnapshot left = new SchemaSnapshot("MYSQL", "sample",
                List.of(leftUsers, orders), false, 200);

        // right(운영): users 변형(email 추가, name 타입 변경, idx_name 유니크로 변경, idx_email 추가) + audit 신규
        TableSchema rightUsers = new TableSchema("users",
                List.of(col("id", "bigint", false, 1), col("name", "varchar(255)", true, 2),
                        col("email", "varchar(200)", false, 3)),
                List.of(new IndexSchema("pk_users", List.of("id"), true),
                        new IndexSchema("idx_name", List.of("name"), true),
                        new IndexSchema("idx_email", List.of("email"), false)));
        TableSchema audit = new TableSchema("audit",
                List.of(col("id", "bigint", false, 1)), List.of());
        SchemaSnapshot right = new SchemaSnapshot("MYSQL", "sample",
                List.of(rightUsers, audit), false, 200);

        SchemaDiffService.SchemaDiff diff = service.diff(left, right);

        assertFalse(diff.identical());
        assertNull(diff.warning(), "같은 기종·미절단이면 경고 없음");

        // 테이블: audit 추가(right에만), orders 삭제(left에만)
        assertEquals(List.of("audit"), diff.addedTables().stream().map(TableSchema::name).toList());
        assertEquals(List.of("orders"), diff.removedTables().stream().map(TableSchema::name).toList());

        // 변경 테이블: users 하나
        assertEquals(1, diff.changedTables().size());
        SchemaDiffService.TableDiff td = diff.changedTables().get(0);
        assertEquals("users", td.table());

        // 컬럼: email 추가, 삭제 없음, name 타입 변경
        assertEquals(List.of("email"), td.addedColumns().stream().map(ColumnSchema::name).toList());
        assertTrue(td.removedColumns().isEmpty());
        assertEquals(1, td.changedColumns().size());
        SchemaDiffService.ColumnChange cc = td.changedColumns().get(0);
        assertEquals("name", cc.name());
        assertEquals("varchar(100)", cc.leftType());
        assertEquals("varchar(255)", cc.rightType());

        // 인덱스: idx_email 추가, 삭제 없음, idx_name 유니크 변경
        assertEquals(List.of("idx_email"), td.addedIndexes().stream().map(IndexSchema::name).toList());
        assertTrue(td.removedIndexes().isEmpty());
        assertEquals(1, td.changedIndexes().size());
        SchemaDiffService.IndexChange ic = td.changedIndexes().get(0);
        assertEquals("idx_name", ic.name());
        assertFalse(ic.left().unique());
        assertTrue(ic.right().unique());
    }

    @Test
    void 구조가_같으면_identical이고_변경목록이_비어있다() {
        TableSchema t = new TableSchema("users",
                List.of(col("id", "bigint", false, 1)),
                List.of(new IndexSchema("pk_users", List.of("id"), true)));
        // ordinalPosition만 다르게 해도 diff 신호가 아니어야 한다(타입·nullable만 비교)
        TableSchema tReordered = new TableSchema("users",
                List.of(col("id", "bigint", false, 5)),
                List.of(new IndexSchema("pk_users", List.of("id"), true)));
        SchemaSnapshot left = new SchemaSnapshot("POSTGRESQL", "sample", List.of(t), false, 200);
        SchemaSnapshot right = new SchemaSnapshot("POSTGRESQL", "sample", List.of(tReordered), false, 200);

        SchemaDiffService.SchemaDiff diff = service.diff(left, right);

        assertTrue(diff.identical());
        assertTrue(diff.addedTables().isEmpty());
        assertTrue(diff.removedTables().isEmpty());
        assertTrue(diff.changedTables().isEmpty());
    }

    @Test
    void 기종이_다르면_타입_표기_차이_경고를_싣는다() {
        SchemaSnapshot mysql = new SchemaSnapshot("MYSQL", "sample",
                List.of(new TableSchema("users", List.of(col("id", "bigint", false, 1)), List.of())),
                false, 200);
        SchemaSnapshot postgres = new SchemaSnapshot("POSTGRESQL", "sample",
                List.of(new TableSchema("users", List.of(col("id", "bigint", false, 1)), List.of())),
                false, 200);

        SchemaDiffService.SchemaDiff diff = service.diff(mysql, postgres);

        assertNotNull(diff.warning());
        assertTrue(diff.warning().contains("기종이 다릅니다"));
    }

    @Test
    void 상한에_잘린_스냅샷이면_부분비교_경고를_싣는다() {
        SchemaSnapshot left = new SchemaSnapshot("MYSQL", "sample", List.of(), true, 200);
        SchemaSnapshot right = new SchemaSnapshot("MYSQL", "sample", List.of(), false, 200);

        SchemaDiffService.SchemaDiff diff = service.diff(left, right);

        assertNotNull(diff.warning());
        assertTrue(diff.warning().contains("부분 비교"));
    }
}
