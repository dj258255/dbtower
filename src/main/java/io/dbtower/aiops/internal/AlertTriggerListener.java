package io.dbtower.aiops.internal;

import io.dbtower.aiops.AiOperationRequest;
import io.dbtower.aiops.AiOperationService;
import io.dbtower.aiops.AiOperationTrigger;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.alert.AlertRaisedEvent;
import io.dbtower.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;

/**
 * 나간 경보를 AI 운영 작업으로 이어 붙인다. 기본은 꺼져 있다 — 켜면 경보마다 모델 호출이 생긴다.
 *
 * <p>유형은 경보 출처로만 고른다(회귀 감지 -> 회귀 원인, 운영 경보 -> 장애 초기 진단). 경보 문장의 단어로 고르면
 * 경보 문구를 고치는 순간 조용히 다른 분석이 돈다. 요청자를 인스턴스 단위(alert:이름)로 두어, 같은 인스턴스에서 경보가
 * 쏟아져도 요청자별 진행 중 작업 상한에 묶인다. 접수 실패는 경보를 막지 않는다 — 감지기 스레드로 예외를 올리지 않는다.</p>
 */
@Component
public class AlertTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(AlertTriggerListener.class);

    private final AiOperationService service;
    private final RegistryService registry;
    private final boolean enabled;
    private final String replyChannel;

    public AlertTriggerListener(AiOperationService service, RegistryService registry,
                                @Value("${dbtower.aiops.alert-triggers.enabled:false}") boolean enabled,
                                @Value("${dbtower.aiops.alert-triggers.reply-channel:}") String replyChannel) {
        this.service = service;
        this.registry = registry;
        this.enabled = enabled;
        this.replyChannel = replyChannel == null || replyChannel.isBlank() ? null : replyChannel.trim();
    }

    @EventListener
    public void onAlert(AlertRaisedEvent event) {
        if (!enabled || event.instanceId() == null) {
            return;
        }
        AiOperationType type = event.source() == AlertRaisedEvent.Source.REGRESSION
                ? AiOperationType.REGRESSION_EXPLANATION : AiOperationType.INCIDENT_TRIAGE;
        // 같은 감지의 재발행(분 단위)은 같은 작업이다 — 경보가 다음 폴에서 다시 나가도 requestId 멱등이 막는다
        String requestId = "alert:" + event.source() + ":" + event.instanceId() + ":"
                + event.raisedAt().truncatedTo(ChronoUnit.MINUTES);
        String prompt = "방금 나간 경보의 원인을 분석해줘. 경보 내용: " + String.join(" / ", event.findings());
        try {
            // 범위는 인스턴스의 담당 팀이다 — 그 팀 사람만 결과를 본다
            String team = registry.findOptional(event.instanceId()).map(i -> i.getTeamLabel()).orElse(null);
            service.submit(new AiOperationRequest(requestId, type, event.instanceId(), event.windowMinutes(),
                    prompt.length() > AiOperationRequest.PROMPT_MAX ? prompt.substring(0, AiOperationRequest.PROMPT_MAX) : prompt,
                    AiOperationTrigger.ALERT, "alert:" + event.instanceName(), team, replyChannel, null));
        } catch (RuntimeException e) {
            log.warn("경보 후속 AI 작업 접수 실패 instance={} source={}: {}", event.instanceName(), event.source(),
                    e.getMessage());
        }
    }
}
