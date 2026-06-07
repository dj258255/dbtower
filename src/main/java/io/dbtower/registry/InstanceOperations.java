package io.dbtower.registry;

/**
 * registry 모듈이 필요로 하는 최소한의 "연결 능력" — 구현은 operator 모듈에 있다.
 *
 * 원래 RegistryService가 DbmsOperatorFactory/ConnectionPools를 직접 썼는데,
 * 그러면 registry -> operator -> registry(엔티티) 순환이 생긴다. Spring Modulith의
 * 모듈 검증이 이 순환을 잡아냈고, 의존을 역전시켜(registry가 인터페이스를 소유,
 * operator가 구현) 방향을 operator -> registry 한쪽으로 정리했다.
 */
public interface InstanceOperations {

    /** 실제 접속해 상태를 확인한다 — 등록 검증과 헬스체크 API가 사용 */
    HealthStatus health(DatabaseInstance instance);

    /** 삭제된 인스턴스의 커넥션 풀/클라이언트 정리 — 안 하면 삭제된 대상의 커넥션이 남는다 */
    void release(Long instanceId);
}
