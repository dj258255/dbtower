package io.dbtower.insight;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 활성 세션 샘플 한 행 (ASH) — 한 시점의 한 세션.
 *
 * <p>{@link io.dbtower.operator.model.SessionInfo}가 "지금"의 조회 결과라면, 이것은 그것을
 * 시각과 함께 남긴 것이다. 둘의 차이는 영속 여부뿐이고, 그 차이가 "장애가 끝난 뒤에도
 * 누가 누구를 막았는지 답할 수 있는가"를 가른다.
 *
 * @param sampledAt        event time — 샘플러가 이 틱을 찍기로 한 시각. 롤업·대사의 기준축
 * @param ingestedAt       processing time — 저장된 시각. sampledAt과의 차가 e2e 지연
 * @param samplerRunId     샘플러 JVM 1회 기동. 결번 판정은 이 범위 안에서만 유효하다
 * @param sampleSeq        인스턴스별 단조 증가 — 결측을 결번으로 드러내기 위한 것
 * @param blockedByPid     나를 막고 있는 세션. 이 컬럼 하나 때문에 이 레코드가 존재한다
 * @param queryFingerprint 쿼리 원문 대신 정규화 해시(부피·개인정보). null 가능
 */
public record AshSample(
        long instanceId,
        LocalDateTime sampledAt,
        LocalDateTime ingestedAt,
        UUID samplerRunId,
        long sampleSeq,
        long pid,
        String username,
        String state,
        String waitEvent,
        String waitCategory,
        Long blockedByPid,
        String queryFingerprint,
        double elapsedMs
) {
}
