package io.dbtower.registry;

/**
 * 인스턴스 등록 해제(삭제) 이벤트 — 인메모리 상태 정리 훅의 배선점(WS-B B-1).
 *
 * <p>삭제 시 DB 자식 행은 FK ON DELETE CASCADE(V10)가 지우지만, 폴러들이 폴 사이에 들고 있는
 * <b>인메모리 상태</b>(히스토그램 스냅샷·데드락 카운터/시그·쿨다운·백오프)는 DB가 모른다. 그대로 두면
 * 삭제된 인스턴스의 키가 맵에 영구 잔존해 장기 가동 시 누수가 된다.
 *
 * <p>왜 이벤트인가: 이 상태들은 operator/alert/insight 등 서로 다른 모듈에 흩어져 있다. RegistryService
 * (registry 모듈)가 그 컴포넌트들을 직접 참조하면 Modulith 순환이 생긴다. 이벤트를 registry에서 발행하고
 * 각 모듈이 {@code @EventListener}로 자기 상태만 비우면, 구조적 의존은 이미 존재하는 "각 모듈 -> registry"
 * 한 방향뿐이라 순환 없이 배선된다.
 *
 * @param instanceId 삭제된 인스턴스 id
 */
public record InstanceDeletedEvent(long instanceId) {
}
