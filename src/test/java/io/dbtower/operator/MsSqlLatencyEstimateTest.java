package io.dbtower.operator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL Server 레이턴시 백분위 근사(B-2, ESTIMATED)의 순수 계산부만 커넥션 없이 못 박는다.
 * Query Store 조회·게이트·단위 환산은 라이브 검증으로 확인하고, 여기서는 산식(avg + z×stdev를 max로 캡)의
 * 불변식만 검증한다. 값은 모두 ms 단위.
 */
class MsSqlLatencyEstimateTest {

    @Test
    void 추정치가_max_미만이면_그_값을_소수둘째자리로_반올림한다() {
        // avg=10, stdev=2, max=100 → p95 = 10 + 1.645*2 = 13.29 (max 100보다 작으니 캡 안 걸림)
        assertThat(MsSqlOperator.estimateCapped(10, 2, 100, MsSqlOperator.Z95)).isEqualTo(13.29);
        // p99 = 10 + 2.326*2 = 14.652 → 14.65
        assertThat(MsSqlOperator.estimateCapped(10, 2, 100, MsSqlOperator.Z99)).isEqualTo(14.65);
    }

    @Test
    void 추정치가_max를_넘으면_max로_캡한다() {
        // avg=10, stdev=100, max=20 → 추정 10 + 1.645*100 = 174.5 지만 관측 최대 20을 넘을 수 없다 → 20
        assertThat(MsSqlOperator.estimateCapped(10, 100, 20, MsSqlOperator.Z95)).isEqualTo(20.0);
        // p99도 동일하게 max로 캡
        assertThat(MsSqlOperator.estimateCapped(10, 100, 20, MsSqlOperator.Z99)).isEqualTo(20.0);
    }

    @Test
    void stdev가_0이면_추정치는_평균이다() {
        // 표준편차가 없으면(단일 실행 등) 근사 = 평균, max가 더 크므로 캡도 안 걸린다
        assertThat(MsSqlOperator.estimateCapped(42.5, 0, 100, MsSqlOperator.Z95)).isEqualTo(42.5);
        assertThat(MsSqlOperator.estimateCapped(42.5, 0, 100, MsSqlOperator.Z99)).isEqualTo(42.5);
    }

    @Test
    void p99_z값이_p95_z값보다_커서_같은_입력에서_p99가_p95_이상이다() {
        assertThat(MsSqlOperator.Z99).isGreaterThan(MsSqlOperator.Z95);
        // 캡이 걸리지 않는 범위에서는 p99 >= p95 (z가 더 크므로)
        Double p95 = MsSqlOperator.estimateCapped(10, 5, 1000, MsSqlOperator.Z95);
        Double p99 = MsSqlOperator.estimateCapped(10, 5, 1000, MsSqlOperator.Z99);
        assertThat(p99).isGreaterThanOrEqualTo(p95);
    }

    @Test
    void 추정치가_정확히_max와_같으면_max를_그대로_쓴다() {
        // avg + z*stdev == max 인 경계: 캡이 값을 바꾸지 않는다
        assertThat(MsSqlOperator.estimateCapped(10, 0, 10, MsSqlOperator.Z95)).isEqualTo(10.0);
    }
}
