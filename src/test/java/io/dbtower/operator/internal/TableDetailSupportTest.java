package io.dbtower.operator.internal;

import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.TableDetail;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 테이블 상세의 식별자 주입 방어와 재구성 DDL 조립 — SHOW CREATE TABLE류는 식별자를 바인딩 못 하므로
 * 문자 집합을 강하게 제한하는 것이 유일한 방어선이다.
 */
class TableDetailSupportTest {

    @Test
    void 외래키_행을_제약별로_접고_나가는_것과_들어오는_것으로_나눈다() {
        List<TableDetailSupport.ForeignKeyRow> rows = List.of(
                new TableDetailSupport.ForeignKeyRow("fk_lines_order", "order_lines", "order_id", "orders", "id", "c", "a"),
                new TableDetailSupport.ForeignKeyRow("fk_lines_order", "order_lines", "order_no", "orders", "no", "c", "a"),
                new TableDetailSupport.ForeignKeyRow("fk_orders_customer", "orders", "customer_id", "customers", "id",
                        "NO ACTION", "NO ACTION"));

        TableDetailSupport.Keys keys = TableDetailSupport.foreignKeys("orders", rows);

        assertThat(keys.outgoing()).containsExactly(new TableDetail.ForeignKey("fk_orders_customer", "orders",
                List.of("customer_id"), "customers", List.of("id"), "NO ACTION", "NO ACTION"));
        // 복합 키는 행 순서대로 열이 짝지어져야 한다 — order_id -> id, order_no -> no
        assertThat(keys.incoming()).containsExactly(new TableDetail.ForeignKey("fk_lines_order", "order_lines",
                List.of("order_id", "order_no"), "orders", List.of("id", "no"), "CASCADE", "NO ACTION"));
    }

    @Test
    void 자기_참조는_양쪽에_나오고_테이블_이름은_대소문자를_무시한다() {
        TableDetailSupport.Keys keys = TableDetailSupport.foreignKeys("emp", List.of(
                new TableDetailSupport.ForeignKeyRow("EMP_MGR_FK", "EMP", "MGR_ID", "EMP", "ID", "SET NULL", null)));

        assertThat(keys.outgoing()).hasSize(1);
        assertThat(keys.incoming()).hasSize(1);
        assertThat(keys.outgoing().get(0).onDelete()).isEqualTo("SET NULL");
        assertThat(keys.outgoing().get(0).onUpdate()).isNull();
    }

    @Test
    void 다른_테이블의_같은_이름_제약은_한_키로_섞이지_않는다() {
        TableDetailSupport.Keys keys = TableDetailSupport.foreignKeys("customers", List.of(
                new TableDetailSupport.ForeignKeyRow("fk_customer", "invoices", "customer_id", "customers", "id", null, null),
                new TableDetailSupport.ForeignKeyRow("fk_customer", "orders", "customer_id", "customers", "id", null, null)));

        assertThat(keys.incoming()).extracting(TableDetail.ForeignKey::table).containsExactly("invoices", "orders");
        assertThat(keys.incoming()).allSatisfy(fk -> assertThat(fk.columns()).containsExactly("customer_id"));
        assertThat(keys.outgoing()).isEmpty();
    }

    @Test
    void 참조_동작은_SQL_문법_표기로_맞춘다() {
        assertThat(TableDetailSupport.referentialAction("NO_ACTION")).isEqualTo("NO ACTION");   // SQL Server
        assertThat(TableDetailSupport.referentialAction("SET_NULL")).isEqualTo("SET NULL");
        assertThat(TableDetailSupport.referentialAction("r")).isEqualTo("RESTRICT");            // PostgreSQL 한 글자
        assertThat(TableDetailSupport.referentialAction("d")).isEqualTo("SET DEFAULT");
        assertThat(TableDetailSupport.referentialAction("CASCADE")).isEqualTo("CASCADE");       // MySQL·Oracle
        assertThat(TableDetailSupport.referentialAction(null)).isNull();
        assertThat(TableDetailSupport.referentialAction(" ")).isNull();
    }

    @Test
    void 현재_스키마_밖의_테이블만_스키마로_한정한다() {
        assertThat(TableDetailSupport.qualify("sample", "sample", "orders")).isEqualTo("orders");
        assertThat(TableDetailSupport.qualify("SAMPLE", "sample", "ORDERS")).isEqualTo("ORDERS");
        assertThat(TableDetailSupport.qualify("sample", "billing", "invoices")).isEqualTo("billing.invoices");
        assertThat(TableDetailSupport.qualify(null, "billing", "invoices")).isEqualTo("invoices");
    }

    @Test
    void 재구성_DDL의_외래키_절은_기본_동작을_생략한다() {
        assertThat(TableDetailSupport.foreignKeyClause(new TableDetail.ForeignKey("fk_a", "orders",
                List.of("customer_id"), "customers", List.of("id"), "NO ACTION", "NO ACTION")))
                .isEqualTo("CONSTRAINT fk_a FOREIGN KEY (customer_id) REFERENCES customers (id)");
        assertThat(TableDetailSupport.foreignKeyClause(new TableDetail.ForeignKey("fk_b", "lines",
                List.of("order_id", "order_no"), "orders", List.of("id", "no"), "CASCADE", null)))
                .isEqualTo("CONSTRAINT fk_b FOREIGN KEY (order_id, order_no) REFERENCES orders (id, no) ON DELETE CASCADE");
    }

    @Test
    void 키를_모르는_생성자는_키_목록을_비워_둔다() {
        TableDetail d = TableDetail.unsupported("orders", "없음");

        assertThat(d.primaryKey()).isEmpty();
        assertThat(d.foreignKeys()).isEmpty();
        assertThat(d.referencedBy()).isEmpty();
    }

    @Test
    void 정상_식별자는_통과한다() {
        assertThatCode(() -> TableDetailSupport.requireIdentifier("orders")).doesNotThrowAnyException();
        assertThatCode(() -> TableDetailSupport.requireIdentifier("user_2024")).doesNotThrowAnyException();
        assertThatCode(() -> TableDetailSupport.requireIdentifier("T$temp#1")).doesNotThrowAnyException();
    }

    @Test
    void 주입_시도_문자는_거부한다() {
        for (String bad : new String[]{
                "orders; DROP TABLE users",   // 세미콜론
                "orders`",                    // 백틱
                "orders\"",                   // 따옴표
                "orders]",                    // 대괄호
                "orders users",               // 공백
                "orders'--",                  // 주석 시작
                "",                           // 빈 문자열
                null}) {
            assertThatThrownBy(() -> TableDetailSupport.requireIdentifier(bad))
                    .as("입력=%s", bad)
                    .isInstanceOf(OperatorException.class);
        }
    }

    @Test
    void 재구성_DDL은_컬럼_PK_인덱스를_조립한다() {
        String ddl = TableDetailSupport.reconstructDdl("orders",
                List.of(new TableDetailSupport.ColumnDef("id", "integer", false, "nextval('seq')"),
                        new TableDetailSupport.ColumnDef("note", "text", true, null)),
                List.of("id"),
                List.of("CREATE INDEX idx_note ON orders (note)"));

        assertThat(ddl).contains("CREATE TABLE orders");
        assertThat(ddl).contains("id integer NOT NULL DEFAULT nextval('seq')");
        assertThat(ddl).contains("note text");        // nullable이라 NOT NULL 없음
        assertThat(ddl).doesNotContain("note text NOT NULL");
        assertThat(ddl).contains("PRIMARY KEY (id)");
        assertThat(ddl).contains("CREATE INDEX idx_note ON orders (note)");
    }

    @Test
    void 테이블_제약_FK_CHECK를_본문에_넣고_콤마가_어긋나지_않는다() {
        String ddl = TableDetailSupport.reconstructDdl("orders",
                List.of(new TableDetailSupport.ColumnDef("id", "integer", false, null),
                        new TableDetailSupport.ColumnDef("qty", "integer", false, null),
                        new TableDetailSupport.ColumnDef("customer_id", "integer", true, null)),
                List.of("id"),
                List.of("CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customer(id)",
                        "CONSTRAINT chk_qty CHECK ((qty > 0))"),
                List.of());

        assertThat(ddl).contains("PRIMARY KEY (id),");   // 뒤에 제약이 더 있으니 콤마가 붙는다
        assertThat(ddl).contains("CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customer(id),");
        assertThat(ddl).contains("CONSTRAINT chk_qty CHECK ((qty > 0))");  // 마지막 본문 라인 — 콤마 없음
        assertThat(ddl).doesNotContain("CHECK ((qty > 0)),");
        assertThat(ddl).endsWith(")");
    }
}
