package io.dbtower.operator.internal;

import io.dbtower.registry.HealthStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * health()는 어떤 이유로 실패하든 <b>예외가 아니라 down</b>을 돌려줘야 한다.
 *
 * <p>실측으로 드러난 문제: 대상이 죽어 있으면 첫 커넥션 시도에서
 * {@code HikariPool$PoolInitializationException}이 나는데, 이건 {@code DataAccessException}이
 * 아니라 순수 {@code RuntimeException}이라 예전 catch를 빠져나갔다. 그 결과
 * {@code GET /api/instances/{id}/health}가 down 상태가 아니라 302(로그인 리다이렉트)를 냈고,
 * 더 나쁘게는 <b>다운 감지가 health()를 부르는 자리에서 예외로 터져 다운 알림이 다시 침묵</b>했다.
 *
 * <p>여기서는 의존성을 null로 준다 — 조회 경로에서 RuntimeException(NPE)이 나는 가장 단순한 형태이고,
 * 검증하려는 성질("실패 종류와 무관하게 down")이 그대로 드러난다.
 */
class HealthFailureTest {

    @Test
    void 조회_경로가_예외를_던져도_down으로_돌려준다() {
        MsSqlOperator op = new MsSqlOperator(null, null, null);

        assertThatCode(op::health).doesNotThrowAnyException();

        HealthStatus status = op.health();
        assertThat(status.up()).isFalse();
        assertThat(status.message()).isNotBlank();   // 사유가 감춰지지 않는다
    }
}
