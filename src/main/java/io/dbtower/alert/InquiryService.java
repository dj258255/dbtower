package io.dbtower.alert;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DB팀 문의 채널 — 분석 결과(쿼리·실행계획·규칙 지적·AI 분석)를 사람이 읽기 좋은 메시지로 묶어
 * WebhookNotifier로 Slack/Discord에 보낸다.
 *
 * 회귀 감지(RegressionDetector)가 "플랫폼이 사람에게 미는 push"라면, 이 문의는 방향은 같되
 * 트리거가 사람인 push다 — 그래서 같은 웹훅 어댑터를 재사용한다.
 *
 * 모듈 경계: alert -> insight 의존은 이미 있으므로(RegressionDetector), 문의를 insight에 두고
 * WebhookNotifier(alert)를 쓰면 insight <-> alert 순환이 된다. 그래서 문의 기능 전체를
 * alert 모듈 안에 둔다. 여기서 참조하는 registry는 alert가 이미 의존하는 방향이라 순환이 아니다.
 */
@Service
public class InquiryService {

    private final RegistryService registryService;
    private final WebhookNotifier notifier;

    public InquiryService(RegistryService registryService, WebhookNotifier notifier) {
        this.registryService = registryService;
        this.notifier = notifier;
    }

    /** 문의 요청 본문 — plan/findings/aiAnalysis/note는 선택(분석을 안 돌리고도 문의 가능) */
    public record InquiryRequest(String sql, String plan, List<String> findings,
                                 String aiAnalysis, String note) {
    }

    /**
     * 결과를 sent로 구분하는 이유 — 웹훅 미설정 시 send()는 조용히 로그만 남긴다.
     * 그대로 200/sent:true를 주면 "보낸 줄 알았는데 아무도 못 받은" 침묵 실패가 된다.
     * 미설정이면 sent:false + 원인을 명시해 사용자가 즉시 알게 한다.
     */
    public record InquiryResult(boolean sent, String reason) {
    }

    public InquiryResult submit(Long instanceId, InquiryRequest req) {
        DatabaseInstance instance = registryService.findById(instanceId);
        if (!notifier.isConfigured()) {
            return new InquiryResult(false, "웹훅 미설정 — DBTOWER_WEBHOOK_URL");
        }
        notifier.send(format(instance, req, currentPrincipal()));
        return new InquiryResult(true, null);
    }

    private static String currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    /**
     * 메시지 포맷 — Slack/Discord 공통. WebhookNotifier가 content/text로 감싸므로 본문 문자열만 만든다.
     * 마크다운은 쓰지 않는다(플레인 텍스트) — 두 플랫폼에서 렌더가 갈리지 않게, 그리고 SQL/플랜의
     * 특수문자가 마크다운으로 오해석되지 않게.
     */
    private static String format(DatabaseInstance instance, InquiryRequest req, String principal) {
        StringBuilder sb = new StringBuilder();
        sb.append("[DBTower DB팀 문의]\n");
        sb.append("인스턴스: ").append(instance.getName()).append(" (").append(instance.getType()).append(")\n");
        sb.append("요청자: ").append(principal).append("\n");

        sb.append("\n쿼리:\n").append(blankToDash(req.sql()));

        if (hasText(req.plan())) {
            sb.append("\n\n실행계획:\n").append(req.plan().strip());
        }
        List<String> findings = req.findings();
        if (findings != null && !findings.isEmpty()) {
            sb.append("\n\n규칙 지적:");
            findings.forEach(f -> sb.append("\n- ").append(f));
        }
        if (hasText(req.aiAnalysis())) {
            sb.append("\n\nAI 분석:\n").append(req.aiAnalysis().strip());
        }
        if (hasText(req.note())) {
            sb.append("\n\n비고:\n").append(req.note().strip());
        }
        return sb.toString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToDash(String s) {
        return hasText(s) ? s.strip() : "(쿼리 없음)";
    }
}
