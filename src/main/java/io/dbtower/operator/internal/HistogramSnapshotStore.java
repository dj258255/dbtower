package io.dbtower.operator.internal;

import io.dbtower.operator.DbmsOperatorFactory;

import io.dbtower.registry.InstanceDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 히스토그램 누적 스냅샷 보관소 (2차 아크 B-1/B-3/B-4 공용).
 *
 * <p>Operator는 {@link DbmsOperatorFactory}가 매 호출 새로 생성하므로(상태 없는 설계),
 * "직전 스냅샷"을 폴 사이에 유지할 곳이 operator 안에는 없다. 이 싱글턴 빈이 그 자리를 맡는다 —
 * 인스턴스·스코프별 누적 카운트 배열을 담아 두고, 다음 호출에서 {@link HistogramPercentile#windowDiff}로
 * 차분한다. 여기서 말하는 "윈도우"는 곧 <b>연속한 두 latencyPercentiles 호출 사이의 구간</b>이다
 * (폴러 주기면 그 주기, 온디맨드면 "마지막으로 본 이후"). 순수 인메모리라 앱 재기동 시 비고,
 * 그러면 각 키의 첫 호출은 직전 스냅샷이 없어 호출자가 "학습 중" 폴백을 탄다.
 */
@Component
public class HistogramSnapshotStore {

    private final Map<String, long[]> snapshots = new ConcurrentHashMap<>();

    /**
     * 현재 스냅샷을 저장하고 직전 값을 돌려준다(원자적 교체). 첫 호출이면 null.
     *
     * @param key     인스턴스·스코프·디제스트를 아우르는 고유 키(호출자가 조합)
     * @param current 이번에 읽은 누적 카운트 배열
     * @return 직전 누적 카운트 배열(없으면 null)
     */
    public long[] swap(String key, long[] current) {
        return snapshots.put(key, current);
    }

    /** 인스턴스 삭제·등록 해제 시 그 인스턴스의 스냅샷을 비운다(키 접두 일치). */
    public void evictInstance(long instanceId) {
        String prefix = instanceId + ":";
        snapshots.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 인스턴스 삭제 이벤트(B-1) — 그 인스턴스의 히스토그램 스냅샷을 비워 인메모리 누수를 막는다. */
    @EventListener
    public void onInstanceDeleted(InstanceDeletedEvent event) {
        evictInstance(event.instanceId());
    }
}
