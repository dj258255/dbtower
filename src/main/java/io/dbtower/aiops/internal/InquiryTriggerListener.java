package io.dbtower.aiops.internal;

import io.dbtower.aiops.AiOperationRequest;
import io.dbtower.aiops.AiOperationService;
import io.dbtower.aiops.AiOperationTrigger;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.alert.InquiryRaisedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 나간 DB팀 문의 뒤에 사실 수집·규칙 판정·AI 1차 소견을 붙인다. 문의 자체는 이미 웹훅으로 나갔고,
 * 이 리스너는 그 뒤를 잇는다 — 그래서 접수 실패가 문의를 되돌리지 않는다.
 *
 * <p>기본이 켜짐인 이유: 경보(AlertTriggerListener)와 달리 사람이 직접 누른 한 건이고, 요청자별 진행 중
 * 작업 상한(기본 3)이 이미 비용을 묶는다. 경보는 자동으로 쏟아지지만 문의는 누른 만큼만 생긴다.</p>
 *
 * <p>리스너는 문의 요청 스레드에서 돈다. 인증 주체가 사람이면 AiOperationService가 본문의 requester를
 * 인증 주체로 덮으므로 여기 실린 requester는 사람 문의에서는 쓰이지 않지만, 자동화 주체가 문의를 올리는
 * 경로를 위해 그대로 싣는다.</p>
 */
@Component
public class InquiryTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(InquiryTriggerListener.class);

    private static final String TRUNCATED = " ...(잘림)";

    private final AiOperationService service;
    private final boolean enabled;

    public InquiryTriggerListener(AiOperationService service,
                                  @Value("${dbtower.aiops.inquiry-trigger.enabled:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @EventListener
    public void onInquiry(InquiryRaisedEvent event) {
        if (!enabled || event.instanceId() == null) {
            return;
        }
        StringBuilder prompt = new StringBuilder("DB팀 문의에 대한 후속 분석. 문의 SQL: ").append(event.maskedSql());
        if (!event.findings().isEmpty()) {
            prompt.append(" / 규칙 지적: ").append(String.join("; ", event.findings()));
        }
        if (event.note() != null && !event.note().isBlank()) {
            prompt.append(" / 비고: ").append(event.note());
        }
        try {
            service.submit(new AiOperationRequest("inquiry:" + event.inquiryId(), AiOperationType.DB_TEAM_INQUIRY,
                    event.instanceId(), 60, cap(prompt.toString()),
                    AiOperationTrigger.WEB, event.requester(), null, null, null));
        } catch (RuntimeException e) {
            log.warn("문의 후속 AI 작업 접수 실패 inquiry={} instance={}: {}", event.inquiryId(),
                    event.instanceName(), e.getMessage());
        }
    }

    /** 상한을 넘으면 잘렸다고 밝힌다. 마지막 글자가 서로게이트 쌍의 앞짝이면 한 칸 물러선다(깨진 글자 방지) */
    private static String cap(String text) {
        if (text.length() <= AiOperationRequest.PROMPT_MAX) {
            return text;
        }
        int end = AiOperationRequest.PROMPT_MAX - TRUNCATED.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + TRUNCATED;
    }
}
