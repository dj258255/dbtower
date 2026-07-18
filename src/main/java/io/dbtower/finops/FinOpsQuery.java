package io.dbtower.finops;

/**
 * 낭비 신호 공개 조회 (운영 병목 아크 B5). FinOpsService(finops.internal)가 구현한다 —
 * 월간 리포트가 finops 내부를 참조하지 않고 이 인터페이스로만 낭비 요약을 읽는다(Modulith 경계).
 */
public interface FinOpsQuery {

    /** 인스턴스 하나의 낭비 신호 요약. 스코프 밖이면 findById가 404. */
    WasteSummary wasteSummary(Long instanceId);
}
