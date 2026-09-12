package io.dbtower.score.internal;

import io.dbtower.operator.model.ResourcePressure;
import io.dbtower.score.internal.SignalContribution.Signal;
import io.dbtower.score.internal.SignalContribution.State;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자원 압박 신호의 정직 규약(162절) — "CPU를 못 읽는다"를 "한가하다"로 바꾸지 않는다.
 *
 * 사용자는 헬스 스코어에 CPU를 넣길 원했지만 CPU는 호스트 지표라 인스턴스에 귀속되지 않는다.
 * 대신 기종이 자기 통계로 답하는 동시 실행 압박을 쓰되, 한도를 모르면 감점하지 않는다 —
 * 절대 수치만으로 많고 적음을 말할 수 없고, 추측해서 깎으면 모르는 것을 나쁘다고 한 것이 된다.
 */
class ResourceSignalTest {

    private static final ScoreWeights W = ScoreWeights.defaults();

    @Test
    void 한도를_모르면_감점하지_않고_판정을_보류한다() {
        // Oracle에서 cpu_count를 못 읽는 경우처럼 한도가 null이면 비율 자체가 없다
        ResourcePressure noLimit = new ResourcePressure(3, null, null, "평균 활성 세션(AAS)", "v$con_sysmetric");

        SignalContribution c = SignalContribution.fromResource(noLimit, W);

        assertThat(noLimit.usedRatio()).isNull();
        assertThat(c.state()).isEqualTo(State.INSUFFICIENT_DATA);
        assertThat(c.penalty()).isZero();
        assertThat(c.counted()).isFalse(); // 점수 계산에서 빠진다 — 0점으로 끌어내리지 않는다
        assertThat(c.summary()).contains("한도를 몰라");
    }

    @Test
    void 여유가_있으면_감점_없이_정상이다() {
        // 라이브 실측값(162절): MySQL Threads_running 2 / max_connections 151
        ResourcePressure calm = new ResourcePressure(2, 151L, null, "실행 중 스레드", "Threads_running / max_connections");

        SignalContribution c = SignalContribution.fromResource(calm, W);

        assertThat(c.state()).isEqualTo(State.OK);
        assertThat(c.penalty()).isZero();
        assertThat(c.signal()).isEqualTo(Signal.RESOURCE);
        // 요약에는 무엇을 읽었는지가 남는다 — 기종마다 세는 단위가 다르기 때문
        assertThat(c.summary()).contains("실행 중 스레드").contains("Threads_running");
    }

    @Test
    void 한도의_대부분을_쓰면_경고로_감점한다() {
        ResourcePressure warn = new ResourcePressure(80, 100L, 0L, "활성 백엔드", "pg_stat_activity");

        SignalContribution c = SignalContribution.fromResource(warn, W);

        assertThat(c.state()).isEqualTo(State.PENALIZED);
        assertThat(c.penalty()).isEqualTo(W.resourceWarn());
        assertThat(c.summary()).contains("여유가 줄고");
    }

    @Test
    void 한도에_근접하면_더_크게_감점한다() {
        ResourcePressure critical = new ResourcePressure(95, 100L, 12L, "활성 백엔드", "pg_stat_activity");

        SignalContribution c = SignalContribution.fromResource(critical, W);

        assertThat(c.penalty()).isEqualTo(W.resourceCritical());
        assertThat(c.penalty()).isGreaterThan(W.resourceWarn());
        assertThat(c.summary()).contains("한도에 근접");
    }

    @Test
    void 사용량이_한도를_넘어도_비율은_1을_넘지_않는다() {
        // 티켓처럼 큐가 따로 잡히는 기종에서 out이 total을 넘는 순간이 있다 — 비율이 100%를 넘어 보이면 안 된다
        ResourcePressure over = new ResourcePressure(9, 4L, 5L, "WiredTiger 티켓", "serverStatus");

        assertThat(over.usedRatio()).isEqualTo(1.0);
        assertThat(SignalContribution.fromResource(over, W).penalty()).isEqualTo(W.resourceCritical());
    }
}
