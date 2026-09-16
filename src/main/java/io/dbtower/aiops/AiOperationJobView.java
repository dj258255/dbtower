package io.dbtower.aiops;

import java.time.OffsetDateTime;

/**
 * 외부 채널과 웹 콘솔에 공개하는 작업 상태. 자격증명·리스 토큰은 담지 않는다.
 *
 * @param submittedBy 실제로 요청을 올린 인증 주체(사람이면 requester와 같고, 게이트웨이면 api-token)
 * @param instanceType 접수 시점의 대상 기종(정기 리포트처럼 대상이 여럿이면 null)
 * @param scopeTeam   접수 시점에 확정한 팀 범위(null이면 전역). 사실 수집은 이 범위를 다시 확인한다
 * @param result      완료된 작업의 결과(없으면 null)
 */
public record AiOperationJobView(
        String jobId,
        String requestId,
        AiOperationType type,
        AiOperationStatus status,
        AiOperationTrigger trigger,
        String requester,
        String submittedBy,
        String scopeTeam,
        Long instanceId,
        String instanceType,
        OffsetDateTime windowFrom,
        OffsetDateTime windowTo,
        String prompt,
        int attempt,
        String replyChannel,
        String replyThread,
        OffsetDateTime requestedAt,
        OffsetDateTime updatedAt,
        OffsetDateTime notifiedAt,
        String failureReason,
        AiOperationResult result) {
}
