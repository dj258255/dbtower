package io.dbtower.aiops;

/**
 * 웹·Slack 게이트웨이·경보·스케줄러가 공통 실행기로 넘기는 작업 요청.
 *
 * <p>requester와 team은 자동화 주체(서비스 토큰)가 사람을 대신해 올릴 때만 쓰인다. 로그인한 사람이 올리면
 * 서비스가 인증 주체의 이름과 팀으로 덮어쓴다 — 본문 값을 믿으면 요청자를 위조할 수 있다.</p>
 *
 * <p>prompt는 지시문이 아니라 데이터다. 실행기는 이 문장으로 대상·도구·권한을 고르지 않는다.
 * 대상은 instanceId로 고정되고, 사실 수집 범위는 작업 유형이 정한다.</p>
 *
 * @param requestId     멱등 키(Slack event_id 등). 비우면 서비스가 만든다
 * @param windowMinutes 분석 구간(분). 비우면 60, 5분~7일로 제한한다
 * @param replyChannel  결과를 돌려보낼 채널(Slack 채널 id 등, 선택)
 * @param replyThread   결과를 붙일 스레드(Slack thread_ts 등, 선택)
 */
public record AiOperationRequest(
        String requestId,
        AiOperationType type,
        Long instanceId,
        Integer windowMinutes,
        String prompt,
        AiOperationTrigger trigger,
        String requester,
        String team,
        String replyChannel,
        String replyThread) {

    public static final int PROMPT_MAX = 2000;

    public AiOperationRequest {
        if (type == null) {
            throw new IllegalArgumentException("작업 유형은 필수입니다");
        }
        if (instanceId == null && type != AiOperationType.PERIODIC_REPORT) {
            throw new IllegalArgumentException("대상 인스턴스는 필수입니다(정기 리포트만 생략 가능)");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("요청 내용은 필수입니다");
        }
        if (prompt.length() > PROMPT_MAX) {
            throw new IllegalArgumentException("요청 내용은 " + PROMPT_MAX + "자 이하여야 합니다");
        }
        if (requestId != null && requestId.length() > 120) {
            throw new IllegalArgumentException("requestId는 120자 이하여야 합니다");
        }
        if (trigger == null) {
            trigger = AiOperationTrigger.API;
        }
    }
}
