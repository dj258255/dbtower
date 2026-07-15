package io.dbtower.operator;

import io.dbtower.operator.internal.HistogramSnapshotStore;

import io.dbtower.registry.InstanceDeletedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-1 인메모리 정리 — 인스턴스가 삭제되면 그 인스턴스의 히스토그램 누적 스냅샷이 접두 일치로 비워져야 한다.
 * (키는 "instanceId:스코프:디제스트" 조합이라 접두 "instanceId:"로 그 인스턴스 것만 골라 지운다.)
 */
class HistogramSnapshotStoreEvictTest {

    @Test
    void 삭제_이벤트는_해당_인스턴스의_스냅샷만_비운다() {
        HistogramSnapshotStore store = new HistogramSnapshotStore();
        store.swap("1:latency:digestA", new long[]{1, 2, 3});
        store.swap("1:latency:digestB", new long[]{4, 5, 6});
        store.swap("2:latency:digestA", new long[]{7, 8, 9});

        store.onInstanceDeleted(new InstanceDeletedEvent(1L));

        // 인스턴스 1의 키는 사라졌으므로 다시 swap하면 직전 값이 없어 null이 돌아온다(= 키 제거됨)
        assertThat(store.swap("1:latency:digestA", new long[]{0})).isNull();
        assertThat(store.swap("1:latency:digestB", new long[]{0})).isNull();
        // 인스턴스 2의 키는 유지 — swap이 직전 배열을 돌려준다
        assertThat(store.swap("2:latency:digestA", new long[]{0})).containsExactly(7, 8, 9);
    }
}
