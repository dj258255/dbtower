package io.dbtower.insight.internal.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A9 백오프 로직 단위 검증 — 죽은 대상 DB를 매 틱 두드리지 않도록 실패가 이어질수록
 * 건너뛸 틱 수를 지수적으로 늘리고, 한 번 성공하면 즉시 정상 주기로 복귀하는지 고정한다.
 * (수집 파이프라인과 무관한 순수 상태 머신이라 협력자는 null로 둔다.)
 */
class SnapshotSchedulerBackoffTest {

    private SnapshotScheduler scheduler() {
        return new SnapshotScheduler(null, null, null, 4);
    }

    @Test
    void 연속_실패는_건너뛸_틱을_지수적으로_늘린다_상한까지() {
        SnapshotScheduler s = scheduler();
        long id = 1L;

        // 1, 2, 4, 8, 16 그리고 상한(MAX_SKIP=16)에서 포화
        assertEquals(1, s.onFailure(id));
        assertEquals(2, s.onFailure(id));
        assertEquals(4, s.onFailure(id));
        assertEquals(8, s.onFailure(id));
        assertEquals(16, s.onFailure(id));
        assertEquals(16, s.onFailure(id), "상한에서 포화되어 무한히 커지지 않는다");
    }

    @Test
    void 한번_성공하면_즉시_정상_주기로_복귀한다() {
        SnapshotScheduler s = scheduler();
        long id = 2L;

        s.onFailure(id); // skip=1
        s.onFailure(id); // skip=2
        s.onSuccess(id);
        // 회복 후엔 벌점이 초기화되어 다음 실패도 1부터 다시 시작
        assertFalse(s.shouldSkip(id), "성공 후엔 건너뛰지 않는다");
        assertEquals(1, s.onFailure(id), "회복 후 첫 실패는 다시 1틱부터");
    }

    @Test
    void 실패_후_건너뛴_뒤_다시_수집을_시도한다() {
        SnapshotScheduler s = scheduler();
        long id = 3L;

        s.onFailure(id); // skip=2 (연속 실패 2회로 만들자)
        s.onFailure(id); // skip=2
        assertTrue(s.shouldSkip(id), "첫 틱은 건너뛴다");
        assertTrue(s.shouldSkip(id), "둘째 틱도 건너뛴다");
        assertFalse(s.shouldSkip(id), "예산 소진 후엔 다시 수집을 시도한다");
    }

    @Test
    void 실패_이력이_없으면_건너뛰지_않는다() {
        assertFalse(scheduler().shouldSkip(99L));
    }
}
