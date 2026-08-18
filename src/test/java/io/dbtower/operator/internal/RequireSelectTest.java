package io.dbtower.operator.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-2: requireSelect가 SELECT 게이트를 통과한 뒤에도 세미콜론으로 이어지는 스택 쿼리(다중문)를
 * 거부하는지 못 박는다. 끝의 단일 세미콜론과 리터럴 안의 세미콜론은 정상이므로 통과해야 한다.
 *
 * <p>다관점 리뷰에서 이 방어가 <b>주석 한 줄로 뚫리는데 이 테스트는 초록불</b>이라는 것이 드러났다.
 * 원인은 검증 케이스가 전부 주석 없는 정직한 입력이었던 것 — "정직한 입력에서 잘 동작한다"를
 * 검증했지 방어를 검증하지 않았다. 아래 우회 케이스들은 그때 실제로 통과했던 입력을 그대로 고정한 것이다.
 * 방어 로직을 다시 손대면 이 테스트가 먼저 빨개져야 한다.
 *
 * <p>requireSelect는 protected라 같은 패키지(io.dbtower.operator.internal)에서 접근 가능하다.
 * 대상 DB에 붙지 않는 순수 문자열 검사라 커넥션 자원(null)이 있어도 안전하게 호출된다.
 */
class RequireSelectTest {

    /** 커넥션 자원을 쓰지 않는 순수 SQL 검사만 호출하므로 의존성은 null로 충분하다. */
    private final MsSqlOperator op = new MsSqlOperator(null, null, null);

    // ---------- 회귀 방어: 리뷰에서 실제로 뚫렸던 우회 ----------

    @Test
    void 블록주석에_숨긴_다중문을_거부한다() {
        // 주석 안 홑따옴표 1개가 "문자열 안" 상태를 켜서 뒤의 세미콜론 검사를 통째로 건너뛰었다
        assertThatThrownBy(() -> op.requireSelect("SELECT 1 /* ' */; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 라인주석에_숨긴_다중문을_거부한다() {
        assertThatThrownBy(() -> op.requireSelect("SELECT 1 -- '\n; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void PostgreSQL_이스케이프_문자열로_숨긴_다중문을_거부한다() {
        // E'...'에서만 백슬래시가 이스케이프다 — 일반 문자열과 같은 규칙으로 보면 여기서 뚫린다
        assertThatThrownBy(() -> op.requireSelect("SELECT E'\\''; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 달러인용으로_숨긴_다중문을_거부한다() {
        assertThatThrownBy(() -> op.requireSelect("SELECT $q$ ' $q$; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 쌍따옴표로_숨긴_다중문을_거부한다() {
        assertThatThrownBy(() -> op.requireSelect("SELECT \"it's\" ; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 회귀 방어: 정상 쿼리를 거부하던 오탐 ----------

    @Test
    void 주석_안의_세미콜론은_다중문이_아니다() {
        // 반대 방향 파손 — 정상 쿼리인데 거부됐다
        assertThatCode(() -> op.requireSelect("SELECT 1 /* ; */")).doesNotThrowAnyException();
        assertThatCode(() -> op.requireSelect("SELECT 1 -- 끝 ; 아님\n")).doesNotThrowAnyException();
    }

    @Test
    void CTE로_시작하는_조회를_허용한다() {
        // WITH는 실무 SQL의 상당 비중인데 startsWith("select")만 봐서 통째로 거부됐다
        assertThatCode(() -> op.requireSelect("WITH t AS (SELECT 1 AS n) SELECT * FROM t"))
                .doesNotThrowAnyException();
    }

    @Test
    void 흔한_컬럼명은_변경_키워드로_오인하지_않는다() {
        // 이 저장소의 DatabaseInstance에도 cluster 필드가 있다 — 예약어가 아닌 이름은 막으면 안 된다
        assertThatCode(() -> op.requireSelect("SELECT cluster, updated_at FROM database_instance"))
                .doesNotThrowAnyException();
        assertThatCode(() -> op.requireSelect("SELECT create_date, audit_delete_log FROM t"))
                .doesNotThrowAnyException();
        // 리터럴 안의 변경 키워드는 데이터다
        assertThatCode(() -> op.requireSelect("SELECT * FROM t WHERE action = 'delete'"))
                .doesNotThrowAnyException();
    }

    // ---------- 데이터 변경 차단 ----------

    @Test
    void 데이터를_바꾸는_CTE를_거부한다() {
        // PostgreSQL data-modifying CTE — SELECT처럼 보이지만 실제로 행을 지운다
        assertThatThrownBy(() -> op.requireSelect(
                "WITH d AS (DELETE FROM orders RETURNING *) SELECT * FROM d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("변경");
    }

    @Test
    void 서브쿼리에_숨긴_변경_키워드를_거부한다() {
        assertThatThrownBy(() -> op.requireSelect(
                "SELECT * FROM (INSERT INTO t VALUES (1) RETURNING *) x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 락을_거는_절을_거부한다() {
        // explainAnalyze는 실제로 실행하므로 FOR UPDATE는 운영 테이블에 행 락을 건다
        assertThatThrownBy(() -> op.requireSelect("SELECT * FROM orders FOR UPDATE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("락");
        assertThatThrownBy(() -> op.requireSelect("SELECT * FROM orders FOR SHARE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 파일로_내보내는_절을_거부한다() {
        assertThatThrownBy(() -> op.requireSelect("SELECT * FROM t INTO OUTFILE '/tmp/x'"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 기존 계약 ----------

    @Test
    void 스택_쿼리는_거부한다() {
        assertThatThrownBy(() -> op.requireSelect("SELECT 1; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 대소문자_섞인_스택_쿼리도_거부한다() {
        assertThatThrownBy(() -> op.requireSelect("select id from t; delete from t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 끝에_붙은_단일_세미콜론은_허용한다() {
        assertThatCode(() -> op.requireSelect("SELECT 1;")).doesNotThrowAnyException();
        assertThatCode(() -> op.requireSelect("SELECT 1;   \n")).doesNotThrowAnyException();
    }

    @Test
    void 리터럴_안의_세미콜론은_허용한다() {
        assertThatCode(() -> op.requireSelect("SELECT ';' AS x")).doesNotThrowAnyException();
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
