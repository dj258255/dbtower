package io.dbtower.aiops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전이 표의 핵심 계약. 선행 구현은 AUTHORIZED로의 전이를 통째로 막아 두어 실행기의 첫 전이가 항상 실패했다 —
 * 그래서 "정상 경로가 끝까지 이어진다"를 가장 먼저 못박는다.
 */
class AiOperationStatusTest {

    @Test
    void 정상_경로는_접수부터_완료까지_이어진다() {
        AiOperationStatus[] path = {AiOperationStatus.RECEIVED, AiOperationStatus.AUTHORIZED,
                AiOperationStatus.COLLECTING, AiOperationStatus.RETRIEVING, AiOperationStatus.ANALYZING,
                AiOperationStatus.VERIFYING, AiOperationStatus.COMPLETED};
        for (int i = 0; i + 1 < path.length; i++) {
            assertThat(path[i].canTransitionTo(path[i + 1])).as(path[i] + " -> " + path[i + 1]).isTrue();
        }
        // 참고 자료 검색은 건너뛸 수 있다
        assertThat(AiOperationStatus.COLLECTING.canTransitionTo(AiOperationStatus.ANALYZING)).isTrue();
    }

    @Test
    void 단계를_건너뛰거나_되돌릴_수_없다() {
        assertThat(AiOperationStatus.RECEIVED.canTransitionTo(AiOperationStatus.ANALYZING)).isFalse();
        assertThat(AiOperationStatus.RECEIVED.canTransitionTo(AiOperationStatus.COMPLETED)).isFalse();
        assertThat(AiOperationStatus.ANALYZING.canTransitionTo(AiOperationStatus.COLLECTING)).isFalse();
        // 분석 소견은 검증을 거쳐야만 완료된다
        assertThat(AiOperationStatus.ANALYZING.canTransitionTo(AiOperationStatus.COMPLETED)).isFalse();
    }

    @Test
    void 종료_상태에서는_재시도만_빠져나간다() {
        for (AiOperationStatus next : AiOperationStatus.values()) {
            assertThat(AiOperationStatus.COMPLETED.canTransitionTo(next)).isFalse();
            assertThat(AiOperationStatus.CANCELLED.canTransitionTo(next)).isFalse();
            assertThat(AiOperationStatus.FAILED.canTransitionTo(next)).isEqualTo(next == AiOperationStatus.RECEIVED);
        }
        // 검증 중에는 취소하지 않는다 — 소견과 사실 대조가 한 트랜잭션에서 끝난다
        assertThat(AiOperationStatus.VERIFYING.canTransitionTo(AiOperationStatus.CANCELLED)).isFalse();
    }
}
