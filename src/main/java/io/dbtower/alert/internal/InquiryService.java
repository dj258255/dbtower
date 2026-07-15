package io.dbtower.alert.internal;

import io.dbtower.analysis.QueryMasker;
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
    private final ReferencedSchemaService referencedSchema;
    private final QueryMasker queryMasker;

    public InquiryService(RegistryService registryService, WebhookNotifier notifier,
                          ReferencedSchemaService referencedSchema, QueryMasker queryMasker) {
        this.registryService = registryService;
        this.notifier = notifier;
        this.referencedSchema = referencedSchema;
        this.queryMasker = queryMasker;
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
        String principal = currentPrincipal();
        // 진단의 핵심 재료 — 참조 테이블의 컬럼·인덱스 구조를 함께 붙인다(심화 아크 2).
        // 대상 조회 실패가 문의 자체를 막지 않게 격리한다(구조가 없어도 쿼리·플랜은 보낸다).
        // 파싱은 원문으로(FROM·JOIN 추출 정확도), 외부 발신 렌더링은 마스킹본으로 — 문의 창의 SQL은
        // 사용자가 직접 친 원문이라 실값(고객 이메일·ID 등)이 그대로 실려 오는 대표 경로다.
        String schemaSummary = safeSchemaSummary(instanceId, req.sql());
        InquiryRequest masked = new InquiryRequest(queryMasker.apply(req.sql()),
                req.plan(), req.findings(), req.aiAnalysis(), req.note());
        notifier.sendEmbed(format(instance, masked, principal, schemaSummary),
                buildEmbed(instance, masked, principal, schemaSummary));
        return new InquiryResult(true, null);
    }

    private String safeSchemaSummary(Long instanceId, String sql) {
        try {
            return ReferencedSchemaService.formatCompact(referencedSchema.describe(instanceId, sql));
        } catch (RuntimeException e) {
            return "";  // 대상 DB 조회 실패 — 구조 없이도 문의는 나간다
        }
    }

    private static String currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    /** 브랜드 인디고 — 아이콘의 중심 노드 색과 같다. */
    private static final int EMBED_COLOR = 0x6366F1;

    /**
     * Discord embed 카드 — 같은 내용을 필드로 구조화한다(제목/요청자/쿼리/실행계획/규칙/AI/비고).
     * SQL·실행계획은 코드블록으로 감싸되, Discord 필드 한도(1024자) 안에서 코드블록이 닫히도록
     * 본문을 먼저 900자로 줄인다 — 한도 절단이 코드블록 백틱을 삼키면 이후 텍스트 전체가 코드로 렌더된다.
     */
    private static WebhookNotifier.Embed buildEmbed(DatabaseInstance instance, InquiryRequest req,
                                                    String principal, String schemaSummary) {
        List<WebhookNotifier.Embed.Field> fields = new java.util.ArrayList<>();
        fields.add(new WebhookNotifier.Embed.Field("요청자", principal, true));
        fields.add(new WebhookNotifier.Embed.Field("인스턴스", instance.getName() + " (" + instance.getType() + ")", true));
        // 담당 팀 — 문의가 어느 팀 소관 DB에 대한 것인지 embed 자체가 말하게 한다(심화 아크 4)
        if (hasText(instance.getTeamLabel())) {
            fields.add(new WebhookNotifier.Embed.Field("담당", instance.getTeamLabel(), true));
        }
        fields.add(new WebhookNotifier.Embed.Field("쿼리", codeBlock("sql", blankToDash(req.sql())), false));
        if (hasText(schemaSummary)) {
            fields.add(new WebhookNotifier.Embed.Field("관련 테이블 구조", codeBlock("", schemaSummary), false));
        }
        if (hasText(req.plan())) {
            fields.add(new WebhookNotifier.Embed.Field("실행계획", codeBlock("", req.plan().strip()), false));
        }
        List<String> findings = req.findings();
        if (findings != null && !findings.isEmpty()) {
            fields.add(new WebhookNotifier.Embed.Field("규칙 지적",
                    String.join("\n", findings.stream().map(f -> "- " + f).toList()), false));
        }
        if (hasText(req.aiAnalysis())) {
            fields.add(new WebhookNotifier.Embed.Field("AI 분석", req.aiAnalysis().strip(), false));
        }
        if (hasText(req.note())) {
            fields.add(new WebhookNotifier.Embed.Field("비고", req.note().strip(), false));
        }
        return new WebhookNotifier.Embed("DBTower DB팀 문의", EMBED_COLOR, fields);
    }

    private static String codeBlock(String lang, String body) {
        String clipped = body.length() > 900 ? body.substring(0, 892) + "… (잘림)" : body;
        return "```" + lang + "\n" + clipped + "\n```";
    }

    /**
     * 메시지 포맷 — Slack/Discord 공통. WebhookNotifier가 content/text로 감싸므로 본문 문자열만 만든다.
     * 마크다운은 쓰지 않는다(플레인 텍스트) — 두 플랫폼에서 렌더가 갈리지 않게, 그리고 SQL/플랜의
     * 특수문자가 마크다운으로 오해석되지 않게.
     */
    private static String format(DatabaseInstance instance, InquiryRequest req, String principal,
                                 String schemaSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("[DBTower DB팀 문의]\n");
        sb.append("인스턴스: ").append(instance.getName()).append(" (").append(instance.getType()).append(")\n");
        sb.append("요청자: ").append(principal).append("\n");

        sb.append("\n쿼리:\n").append(blankToDash(req.sql()));

        if (hasText(schemaSummary)) {
            sb.append("\n\n관련 테이블 구조:\n").append(schemaSummary);
        }
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
