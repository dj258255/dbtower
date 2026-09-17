package io.dbtower.insight;

import java.time.LocalDateTime;

/**
 * 인스턴스 하나의 최근 스냅샷 수집 결과(#72). 기록이 없으면 아직 한 번도 수집을 시도하지 않은 것이다.
 *
 * @param stage 연속 실패 중일 때 어디서 실패했는지 — TARGET(대상 통계 조회) / STORE(플랫폼 저장). 성공하면 null
 */
public record CollectionStatus(Long instanceId, LocalDateTime lastSuccessAt, LocalDateTime lastFailureAt,
                               int consecutiveFailures, String stage) {

    public static final String TARGET = "TARGET";
    public static final String STORE = "STORE";

    public boolean failing() {
        return consecutiveFailures > 0;
    }
}
