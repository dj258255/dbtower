package io.dbtower.alert.internal;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.dbtower.review.ReviewDecidedEvent;
import io.dbtower.review.ReviewSubmittedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 리뷰 이벤트 → Discord 카드 (운영 병목 아크 B2, R5). review 모듈은 카드 발송을 이 리스너에
 * 이벤트로 위임한다 — WebhookNotifier·AlertEmbeds는 alert 내부라 review가 직접 못 쓰기 때문
 * (Modulith 경계). 구조적 의존은 이미 존재하는 "alert -> registry"와 새 "alert -> review(이벤트 타입)"
 * 한 방향뿐이라 순환이 없다(InstanceDeletedEvent와 같은 패턴).
 */
@Component
public class ReviewAlertListener {

    private final RegistryService registryService;
    private final WebhookNotifier notifier;
    private final String baseUrl;

    public ReviewAlertListener(RegistryService registryService, WebhookNotifier notifier,
                               @Value("${dbtower.base-url:}") String baseUrl) {
        this.registryService = registryService;
        this.notifier = notifier;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    @EventListener
    public void onSubmitted(ReviewSubmittedEvent e) {
        if (!notifier.isConfigured()) {
            return;
        }
        DatabaseInstance instance = registryService.findById(e.instanceId());
        String deeplink = baseUrl.isBlank() ? null
                : baseUrl + "/?instance=" + e.instanceId() + "&view=review&review=" + e.reviewId();
        String fallback = "[DBTower 변경 리뷰 요청 #" + e.reviewId() + "] " + instance.getName()
                + " — 요청자 " + e.requester() + "\n" + e.maskedSql();
        notifier.sendEmbed(fallback, e.instanceId(), AlertEmbeds.forReviewRequest(
                instance, e.reviewId(), e.requester(), e.maskedSql(), e.findings(),
                e.aiOpinion(), e.parseLimited(), deeplink));
    }

    @EventListener
    public void onDecided(ReviewDecidedEvent e) {
        if (!notifier.isConfigured()) {
            return;
        }
        DatabaseInstance instance = registryService.findById(e.instanceId());
        String fallback = "[DBTower 변경 리뷰 #" + e.reviewId() + " " + (e.approved() ? "승인" : "반려")
                + "] " + instance.getName() + " — 결정자 " + e.decidedBy();
        notifier.sendEmbed(fallback, e.instanceId(), AlertEmbeds.forReviewDecision(
                instance, e.reviewId(), e.approved(), e.decidedBy(), e.comment(), e.onlineDdlHint()));
    }
}
