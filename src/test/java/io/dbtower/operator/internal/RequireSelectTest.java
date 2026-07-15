package io.dbtower.operator.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-2: requireSelect가 SELECT 게이트를 통과한 뒤에도 세미콜론으로 이어지는 스택 쿼리(다중문)를
 * 거부하는지 못 박는다. 끝의 단일 세미콜론과 리터럴 안의 세미콜론은 정상이므로 통과해야 한다.
 *
 * <p>requireSelect는 protected라 같은 패키지(io.dbtower.operator)에서 접근 가능하다. 대상 DB에 붙지
 * 않는 순수 문자열 검사라 커넥션 자원(null)이 있어도 안전하게 호출된다.
 */
class RequireSelectTest {

    /** 커넥션 자원을 쓰지 않는 순수 SQL 검사만 호출하므로 의존성은 null로 충분하다. */
    private final MsSqlOperator op = new MsSqlOperator(null, null, null);

    @Test
    void 스택_쿼리는_거부한다() {
        assertThatThrownBy(() -> op.requireSelect("SELECT 1; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("단일 SELECT");
    }

    @Test
    void 대소문자_섞인_스택_쿼리도_거부한다() {
        assertThatThrownBy(() -> op.requireSelect("select id from t; delete from t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 끝에_붙은_단일_세미콜론은_허용한다() {
        assertThatCode(() -> op.requireSelect("SELECT 1;")).doesNotThrowAnyException();
        // 끝 세미콜론 뒤 공백/개행만 있어도 정상 종결로 본다
        assertThatCode(() -> op.requireSelect("SELECT 1;   \n")).doesNotThrowAnyException();
    }

    @Test
    void 리터럴_안의_세미콜론은_허용한다() {
        assertThatCode(() -> op.requireSelect("SELECT ';' AS x")).doesNotThrowAnyException();
        // 이스케이프된 따옴표('')를 포함한 리터럴 안의 세미콜론도 데이터로 취급
        assertThatCode(() -> op.requireSelect("SELECT 'a;''b;c' AS x")).doesNotThrowAnyException();
    }

    @Test
    void 정상_SELECT는_통과한다() {
        assertThatCode(() -> op.requireSelect("SELECT id, name FROM products WHERE id = 1"))
                .doesNotThrowAnyException();
    }

    @Test
    void SELECT가_아니면_기존대로_거부한다() {
        assertThatThrownBy(() -> op.requireSelect("DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SELECT");
        assertThatThrownBy(() -> op.requireSelect(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
