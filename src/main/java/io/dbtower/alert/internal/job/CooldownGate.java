package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.persistence.CooldownStore;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 경보 쿨다운 — "판정과 동시에 확정"하지 않고 <b>전송이 성공한 뒤에만</b> 확정하는 규율. 웹훅이 잠깐
 * 죽거나 레이트리밋에 걸린 순간의 경보가 재감지조차 안 되고 영구 소실되던 회귀를 막는다.
 * 네 감지기(회귀·이상·운영 경보·split-brain)가 이 게이트를 쓴다. detect()가 ShedLock + fixedDelay로
 * 한 시점에 한 번만 도는 단일 흐름이라 pending은 평범한 리스트로 충분하다.
 *
 * <p>마지막 전송 시각은 {@link CooldownStore}에 둔다. 기본은 인메모리이고 감지기가 스프링에서 뜰 때
 * {@link #attach}로 메타 DB 저장소가 붙는다 — 재기동해도 쿨다운이 이어진다(170절 4번).
 * 키에 감지기 이름을 앞에 붙여 감지기끼리 같은 테이블을 나눠 쓴다.</p>
 */
class CooldownGate {

    private final List<String> pending = new ArrayList<>();
    private final int cooldownMinutes;
    private final String namespace;
    private CooldownStore store = CooldownStore.inMemory();

    CooldownGate(int cooldownMinutes, String namespace) {
        this.cooldownMinutes = cooldownMinutes;
        this.namespace = namespace + ":";
    }

    /** 영속 저장소로 바꾼다. 감지기가 뜰 때 한 번 — 첫 detect()보다 먼저 불린다(세터 주입) */
    void attach(CooldownStore store) {
        this.store = store;
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
        if (pending.isEmpty()) {
            return;
        }
        store.record(pending.stream().map(this::scoped).toList(), now);
        pending.clear();
        // 쿨다운이 끝난 행은 판정에 다시 쓰이지 않는다 — 쿼리 식별자가 바뀔 때마다 키가 늘어 테이블이 자라지 않게 한다
        store.pruneBefore(namespace, now.minusMinutes(cooldownMinutes));
    }

    /** 전송 실패·무발사 — pending을 버린다(다음 폴에서 같은 신호를 다시 감지해 재시도). */
    void clearPending() {
        pending.clear();
    }

    /** 즉시 흐름용(split-brain) — pending을 거치지 않고 쿨다운 여부만 확인한다. */
    boolean isCoolingDown(String key, LocalDateTime now) {
        LocalDateTime last = store.lastAlerted(scoped(key));
        return last != null && last.plusMinutes(cooldownMinutes).isAfter(now);
    }

    /** 즉시 흐름용(split-brain) — 전송 성공 후 직접 확정. */
    void mark(String key, LocalDateTime now) {
        store.record(List.of(scoped(key)), now);
    }

    /** 인스턴스 삭제 — "instanceId:..." 접두 키를 제거한다(같은 id 재사용 시 낡은 기준선 오판 방지). */
    void evictPrefix(long instanceId) {
        store.evictPrefix(namespace + instanceId + ":");
    }

    private String scoped(String key) {
        return namespace + key;
    }
}
