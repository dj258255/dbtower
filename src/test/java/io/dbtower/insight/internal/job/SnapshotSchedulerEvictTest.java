package io.dbtower.insight.internal.job;

import io.dbtower.registry.InstanceDeletedEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-1 인메모리 정리 — 인스턴스가 삭제되면 그 인스턴스의 백오프 상태가 맵에서 사라져야 한다.
 * 안 지우면 삭제된 id의 상태가 영구 잔존하고, 같은 id가 재사용될 때 낡은 벌점으로 오판한다.
 */
class SnapshotSchedulerEvictTest {

    private SnapshotScheduler scheduler() {
        return new SnapshotScheduler(null, null, null, 4);
    }

    @Test
    void 삭제_이벤트는_백오프_상태를_비운다() {
        SnapshotScheduler s = scheduler();
        long id = 7L;

        s.onFailure(id); // skip=1, 연속실패=1로 상태 생성
        s.onInstanceDeleted(new InstanceDeletedEvent(id));

        // 상태가 사라졌으므로 건너뛰지 않고, 다음 실패는 다시 1틱부터 시작한다
        assertFalse(s.shouldSkip(id), "삭제 후엔 백오프 상태가 없어 건너뛰지 않는다");
        assertEquals(1, s.onFailure(id), "삭제로 벌점이 초기화되어 첫 실패는 다시 1부터");
    }

    @Test
    void evict는_해당_id만_지운다() {
        SnapshotScheduler s = scheduler();
        s.onFailure(1L);
        s.onFailure(2L);
        s.evict(1L);

        assertFalse(s.shouldSkip(1L), "지운 id는 상태 없음");
        assertTrue(s.shouldSkip(2L), "다른 id의 백오프는 유지된다");
    }
}
