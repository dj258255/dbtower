package io.dbtower.insight.internal;

import io.dbtower.insight.internal.LiveSessionHub.LiveFrame;

import java.util.Optional;

/**
 * 노드 사이 실시간 프레임 교환(VERIFICATION 144절) — 조회권을 쥔 노드가 올리고, 나머지 노드가 읽어 넘긴다.
 * 네트워크 없이 허브를 검증하려고 인터페이스로 둔다.
 */
public interface LiveFrameStore {

    /** @param ageMs 올린 뒤 흐른 시간 — 저장소 시계로 잰다(노드 시계 오차와 무관하게) */
    record Stored(LiveFrame frame, long ageMs) {
    }

    /** 프레임을 올리고 저장소가 매긴 seq를 돌려준다. 프레임 안의 seq는 무시된다. */
    long publish(LiveFrame frame);

    Optional<Stored> latest(long instanceId);
}
