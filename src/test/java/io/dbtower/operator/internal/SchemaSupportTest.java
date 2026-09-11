package io.dbtower.operator.internal;

import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.model.ForeignKey;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스키마 트리 조립(150절) — 테이블과 뷰를 나누고 기본키 열을 붙인다. 기종별 SQL은 평평한 행만 넘기고 묶는 일은 여기서 한다.
 */
class SchemaSupportTest {

    private static SchemaSupport.ColumnRow col(String table, String name, int pos) {
        return new SchemaSupport.ColumnRow(table, new ColumnSchema(name, "int", false, pos));
    }

    @Test
    void 종류를_주면_뷰를_가르고_기본키는_primary_인덱스_행에서_얻는다() {
        SchemaSnapshot s = SchemaSupport.build("POSTGRESQL", "sample",
                List.of(col("orders", "id", 1), col("orders", "customer_id", 2), col("order_summary", "status", 1)),
                List.of(new SchemaSupport.IndexColumnRow("orders", "orders_pkey", "id", true, true),
                        new SchemaSupport.IndexColumnRow("orders", "idx_orders_customer", "customer_id", false, false)),
                Map.of("order_summary", TableSchema.VIEW, "orders", TableSchema.TABLE), Map.of(), 200);

        TableSchema orders = s.tables().get(0);
        TableSchema summary = s.tables().get(1);
        assertThat(orders.kind()).isEqualTo(TableSchema.TABLE);
        assertThat(orders.primaryKey()).containsExactly("id");
        assertThat(orders.indexes()).extracting("name").containsExactly("orders_pkey", "idx_orders_customer");
        assertThat(summary.name()).isEqualTo("order_summary");
        assertThat(summary.kind()).isEqualTo(TableSchema.VIEW);
        assertThat(summary.primaryKey()).isEmpty();
    }

    @Test
    void 복합_기본키는_인덱스_안_순서를_지킨다() {
        SchemaSnapshot s = SchemaSupport.build("MYSQL", "sample",
                List.of(col("line_items", "order_id", 1), col("line_items", "seq", 2)),
                List.of(new SchemaSupport.IndexColumnRow("line_items", "PRIMARY", "order_id", true, true),
                        new SchemaSupport.IndexColumnRow("line_items", "PRIMARY", "seq", true, true)),
                Map.of(), Map.of(), 200);

        assertThat(s.tables().get(0).primaryKey()).containsExactly("order_id", "seq");
    }

    @Test
    void 인덱스로_기본키를_가릴_수_없는_기종은_따로_준_기본키를_쓴다() {
        // Oracle은 인덱스 딕셔너리에 기본키 표시가 없어 제약조건에서 읽어 넘긴다
        SchemaSnapshot s = SchemaSupport.build("ORACLE", "FREEPDB1",
                List.of(col("CUSTOMERS", "ID", 1)),
                List.of(new SchemaSupport.IndexColumnRow("CUSTOMERS", "SYS_C008123", "ID", true)),
                Map.of(), Map.of("CUSTOMERS", List.of("ID")), 200);

        assertThat(s.tables().get(0).primaryKey()).containsExactly("ID");
        assertThat(s.tables().get(0).kind()).isEqualTo(TableSchema.TABLE);
    }

    @Test
    void 종류와_기본키를_모르는_예전_호출은_모두_테이블_기본키_없음() {
        SchemaSnapshot s = SchemaSupport.build("MSSQL", "sample",
                List.of(col("orders", "id", 1)),
                List.of(new SchemaSupport.IndexColumnRow("orders", "PK_orders", "id", true)), 200);

        assertThat(s.tables().get(0).kind()).isEqualTo(TableSchema.TABLE);
        assertThat(s.tables().get(0).primaryKey()).isEmpty();
        assertThat(new TableSchema("t", List.of(), List.of()).kind()).isEqualTo(TableSchema.TABLE);
    }

    @Test
    void 상한에_잘린_테이블은_기본키_행도_버린다() {
        SchemaSnapshot s = SchemaSupport.build("POSTGRESQL", "sample",
                List.of(col("a", "id", 1), col("b", "id", 1)),
                List.of(new SchemaSupport.IndexColumnRow("b", "b_pkey", "id", true, true)),
                Map.of(), Map.of(), 1);

        assertThat(s.truncated()).isTrue();
        assertThat(s.tables()).extracting(TableSchema::name).containsExactly("a");
        assertThat(s.tables().get(0).primaryKey()).isEmpty();
    }

    @Test
    void 외래키는_제약을_가진_테이블에_붙고_상한_밖_테이블_것은_버린다() {
        // 153절: 구조 스냅샷이 외래키를 담아야 실행 기록의 구조 비교가 제약조건 변화를 본다
        ForeignKey ordersToCustomers = new ForeignKey("fk_orders_customer", "orders", List.of("customer_id"),
                "customers", List.of("id"), "NO ACTION", "NO ACTION");
        ForeignKey lateToOrders = new ForeignKey("fk_late_order", "late", List.of("order_id"),
                "orders", List.of("id"), "CASCADE", null);

        SchemaSnapshot s = SchemaSupport.build("MYSQL", "sample",
                List.of(col("orders", "id", 1), col("orders", "customer_id", 2), col("customers", "id", 1)),
                List.of(new SchemaSupport.IndexColumnRow("orders", "PRIMARY", "id", true, true)),
                Map.of(), Map.of(),
                Map.of("orders", List.of(ordersToCustomers), "late", List.of(lateToOrders)), 2);

        assertThat(s.tables()).extracting(TableSchema::name).containsExactly("orders", "customers");
        assertThat(s.tables().get(0).foreignKeys()).containsExactly(ordersToCustomers);
        // 가리켜지는 쪽(customers)에는 넣지 않는다 — 구조 스냅샷은 제약을 가진 테이블만 담는다
        assertThat(s.tables().get(1).foreignKeys()).isEmpty();
        // 상한(2) 밖이라 스냅샷에 없는 late의 외래키는 어디에도 붙지 않는다
        assertThat(s.tables()).allSatisfy(t -> assertThat(t.foreignKeys()).noneMatch(fk -> fk.name().equals("fk_late_order")));
    }

    @Test
    void 외래키를_모르는_예전_호출은_빈_목록이다() {
        SchemaSnapshot s = SchemaSupport.build("MSSQL", "sample",
                List.of(col("orders", "id", 1)),
                List.of(new SchemaSupport.IndexColumnRow("orders", "PK_orders", "id", true)), 200);

        assertThat(s.tables().get(0).foreignKeys()).isEmpty();
        assertThat(new TableSchema("t", List.of(), List.of()).foreignKeys()).isEmpty();
    }
}
