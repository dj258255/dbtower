package io.dbtower.operator.internal;

import io.dbtower.operator.OperatorException;
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
