package io.dbtower.registry;

/**
 * 인스턴스를 찾을 수 없음(HTTP 404) — 미등록과 <b>스코프 밖(LBAC)</b>이 같은 예외·같은 메시지를 쓴다.
 * 403으로 구분하면 "그 id의 인스턴스가 존재한다"는 사실 자체가 새기 때문(존재 노출 방지, Phase 3).
 */
public class InstanceNotFoundException extends RuntimeException {

    public InstanceNotFoundException(Long id) {
        super("등록되지 않은 인스턴스: " + id);
    }
}
