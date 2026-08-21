package io.dbtower.alert.internal.job;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 경보 쿨다운 — "판정과 동시에 확정"하지 않고 <b>전송이 성공한 뒤에만</b> 확정하는 규율. 웹훅이 잠깐
 * 죽거나 레이트리밋에 걸린 순간의 경보가 재감지조차 안 되고 영구 소실되던 회귀를 막는다.
 * OpsAlertDetector의 인스턴스 단위 감지와 HaObserver의 split-brain이 같은 게이트를 공유한다.
 * detect()가 ShedLock + fixedDelay로 한 시점에 한 번만 도는 단일 흐름이라 pending은 평범한 리스트로 충분하다.
 */
class CooldownGate {

    private final Map<String, LocalDateTime> lastAlerted = new ConcurrentHashMap<>();
    private final List<String> pending = new ArrayList<>();
    private final int cooldownMinutes;

    CooldownGate(int cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
    }

    /** 인스턴스-단위 흐름용 — 쿨다운을 통과하면 pending에 담고 true. 확정은 {@link #commit}(전송 성공)에서. */
    boolean pass(String key, LocalDateTime now) {
        if (isCoolingDown(key, now)) {
            return false;
        }
        pending.add(key);
        return true;
    }

    /** 전송 성공 — 이번 패스에서 통과한 키들의 쿨다운을 그때 확정한다. */
    void commit(LocalDateTime now) {
        pending.forEach(k -> lastAlerted.put(k, now));
        pending.clear();
    }

    /** 전송 실패·무발사 — pending을 버린다(다음 폴에서 같은 신호를 다시 감지해 재시도). */
    void clearPending() {
        pending.clear();
    }

    /** 즉시 흐름용(split-brain) — pending을 거치지 않고 쿨다운 여부만 확인한다. */
    boolean isCoolingDown(String key, LocalDateTime now) {
        LocalDateTime last = lastAlerted.get(key);
        return last != null && last.plusMinutes(cooldownMinutes).isAfter(now);
    }

    /** 즉시 흐름용(split-brain) — 전송 성공 후 직접 확정. */
    void mark(String key, LocalDateTime now) {
        lastAlerted.put(key, now);
    }

    /** 인스턴스 삭제 — "instanceId:..." 접두 키를 제거한다(같은 id 재사용 시 낡은 기준선 오판 방지). */
    void evictPrefix(long instanceId) {
        String prefix = instanceId + ":";
        lastAlerted.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
