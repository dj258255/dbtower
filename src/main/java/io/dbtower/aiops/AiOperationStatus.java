package io.dbtower.aiops;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 비동기 AI 운영 작업의 상태와 허용 전이.
 *
 * <p>승인 대기 상태를 두지 않는다 — 변경 승인은 review 모듈이 단일 권위다. AI 작업이 승인 상태를 따로 가지면
 * 승인 권위가 둘이 되므로, 변경이 필요하면 결과에 approvalRequired만 남기고 기존 변경 요청 티켓으로 보낸다.
 * 알림 전송도 상태가 아니다 — 알림이 실패했다고 분석이 실패한 것은 아니어서 notifiedAt으로 따로 기록한다.</p>
 */
public enum AiOperationStatus {
    RECEIVED,
    AUTHORIZED,
    COLLECTING,
    RETRIEVING,
    ANALYZING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED;

    // 표로 적는 이유: if 조건으로 흩어 두면 "이전 단계로 되돌리기 금지" 같은 규칙이 한 줄 빠져도 티가 나지 않는다.
    // 선행 코드는 AUTHORIZED로의 전이를 통째로 막아 두어 실행기의 첫 전이가 항상 실패했다.
    private static final Map<AiOperationStatus, Set<AiOperationStatus>> NEXT = Map.of(
            RECEIVED, EnumSet.of(AUTHORIZED, FAILED, CANCELLED),
            AUTHORIZED, EnumSet.of(COLLECTING, FAILED, CANCELLED),
            COLLECTING, EnumSet.of(RETRIEVING, ANALYZING, FAILED, CANCELLED),
            RETRIEVING, EnumSet.of(ANALYZING, FAILED, CANCELLED),
            ANALYZING, EnumSet.of(VERIFYING, FAILED, CANCELLED),
            VERIFYING, EnumSet.of(COMPLETED, FAILED),
            // 재시도만 종료 상태에서 빠져나갈 수 있다 — 서비스의 retry()가 시도 횟수와 새 Outbox 이벤트를 함께 만든다
            FAILED, EnumSet.of(RECEIVED),
            COMPLETED, EnumSet.noneOf(AiOperationStatus.class),
            CANCELLED, EnumSet.noneOf(AiOperationStatus.class));

    public boolean canTransitionTo(AiOperationStatus next) {
        return NEXT.get(this).contains(next);
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
