package io.dbtower.advisor;

import io.dbtower.operator.TableBloat;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 블로트 Advisor 판정 검증 — dead ratio·절대량 임계와 통계 노후 신호.
 * 추정치 기반이므로 "삭제 근거"가 아니라 "점검 신호"임을 detail이 명시하는지도 확인한다.
 */
class BloatAdvisorTest {

    private final BloatAdvisor advisor = new BloatAdvisor();

    @Test
    void PostgreSQL만_지원한다() {
        assertThat(advisor.supports(DbmsType.POSTGRESQL)).isTrue();
        assertThat(advisor.supports(DbmsType.MYSQL)).isFalse();
        assertThat(advisor.supports(DbmsType.MONGODB)).isFalse();
    }

    @Test
    void dead_ratio와_절대량이_모두_임계를_넘으면_블로트_후보() {
        // 20% 이상 + 1만 개 이상 → 후보
        List<AdvisorFinding> f = advisor.evaluate(List.of(
                new TableBloat("public.orders", 30_000, 70_000, 0.30, "2026-07-01T00:00:00", 0)));
        assertThat(f).hasSize(1);
        assertThat(f.get(0).title()).contains("블로트 후보").contains("orders");
        assertThat(f.get(0).detail()).contains("추정치"); // 정직 표기
    }

    @Test
    void 비율은_높아도_절대량이_작으면_잡음이라_제외() {
        // 50%지만 죽은 튜플 100개 → 작은 테이블 잡음, 제외
        List<AdvisorFinding> f = advisor.evaluate(List.of(
                new TableBloat("public.tiny", 100, 100, 0.50, null, 0)));
        assertThat(f).isEmpty();
    }

    @Test
    void 블로트는_아니어도_ANALYZE_이후_변경이_많으면_통계노후_후보() {
        List<AdvisorFinding> f = advisor.evaluate(List.of(
                new TableBloat("public.events", 0, 1_000_000, 0.0, "2026-07-01T00:00:00", 200_000)));
        assertThat(f).hasSize(1);
        assertThat(f.get(0).title()).contains("통계 노후");
        assertThat(f.get(0).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void 정상_테이블은_아무_신호도_없다() {
        assertThat(advisor.evaluate(List.of(
                new TableBloat("public.ok", 500, 1_000_000, 0.0005, "2026-07-06T00:00:00", 100))))
                .isEmpty();
    }
}
