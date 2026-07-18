package io.dbtower.alert.internal;

import io.dbtower.analysis.QueryMasker;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private final String baseUrl;

    public InquiryService(RegistryService registryService, WebhookNotifier notifier,
                          ReferencedSchemaService referencedSchema, QueryMasker queryMasker,
                          @Value("${dbtower.base-url:}") String baseUrl) {
        this.registryService = registryService;
        this.notifier = notifier;
        this.referencedSchema = referencedSchema;
        this.queryMasker = queryMasker;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
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
        ReferencedSchemaService.ReferencedSchema schema = safeSchema(instanceId, req.sql());
        String schemaSummary = schema == null ? "" : ReferencedSchemaService.formatCompact(schema);
        InquiryRequest masked = new InquiryRequest(queryMasker.apply(req.sql()),
                req.plan(), req.findings(), req.aiAnalysis(), req.note());
        notifier.sendEmbed(format(instance, masked, principal, schemaSummary), instance.getId(),
                buildEmbed(instance, masked, principal, schema));
        return new InquiryResult(true, null);
    }

    private ReferencedSchemaService.ReferencedSchema safeSchema(Long instanceId, String sql) {
        try {
            return referencedSchema.describe(instanceId, sql);
        } catch (RuntimeException e) {
            return null;  // 대상 DB 조회 실패 — 구조 없이도 문의는 나간다
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
    private WebhookNotifier.Embed buildEmbed(DatabaseInstance instance, InquiryRequest req,
                                             String principal, ReferencedSchemaService.ReferencedSchema schema) {
        List<WebhookNotifier.Embed.Field> fields = new ArrayList<>();
        fields.add(new WebhookNotifier.Embed.Field("요청자", principal, true));
        fields.add(new WebhookNotifier.Embed.Field("인스턴스", instance.getName() + " (" + instance.getType() + ")", true));
        // 담당 팀 — 문의가 어느 팀 소관 DB에 대한 것인지 embed 자체가 말하게 한다(심화 아크 4)
        if (hasText(instance.getTeamLabel())) {
            fields.add(new WebhookNotifier.Embed.Field("담당", instance.getTeamLabel(), true));
        }
        fields.add(new WebhookNotifier.Embed.Field("쿼리", codeBlock("sql", blankToDash(req.sql())), false));
        if (schema != null) {
            addSchemaFields(fields, schema);
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
        // 진단 딥링크(감지 알림과 같은 결) — 클릭하면 콘솔이 이 인스턴스+문의 SQL 진단 질문 프리필로 열린다.
        // base-url 미설정이면 링크 생략. 질문은 짧게(URL 단축 — Discord 마스킹 링크 렌더 조건).
        if (!baseUrl.isBlank()) {
            String question = URLEncoder.encode("이 쿼리가 왜 느린지 분석해줘", StandardCharsets.UTF_8);
            String deeplink = baseUrl + "/?instance=" + instance.getId() + "&diagnose=" + question;
            fields.add(new WebhookNotifier.Embed.Field("진단", AlertEmbeds.link("콘솔에서 진단하기", deeplink), false));
        }
        return new WebhookNotifier.Embed("DBTower DB팀 문의", EMBED_COLOR, fields);
    }

    private static String codeBlock(String lang, String body) {
        String clipped = body.length() > 900 ? body.substring(0, 892) + "… (잘림)" : body;
        return "```" + lang + "\n" + clipped + "\n```";
    }

    /** DDL 코드블록 필드에 붙는 부속 줄(통계·카디널리티) 몫을 남기려 본문을 더 줄인다 */
    private static String ddlBlock(String ddl) {
        String lang = ddl.stripLeading().startsWith("{") || ddl.stripLeading().startsWith("[") ? "json" : "sql";
        String clipped = ddl.length() > 760 ? ddl.substring(0, 752) + "… (잘림)" : ddl;
        return "```" + lang + "\n" + clipped + "\n```";
    }

    /** embed 필드 25개 한도 안에서 DDL 필드로 쓸 수 있는 테이블 수 — 나머지는 요약으로 */
    private static final int DDL_TABLES_MAX = 2;

    /**
     * 관련 테이블 구조 — 테이블 상세 화면과 같은 구성(스키마 정보=DDL·기본 통계·인덱스 카디널리티)을
     * 테이블당 필드 하나로 압축한다. DDL은 기종별 원천 그대로(MySQL=SHOW CREATE TABLE 원문,
     * PG/MSSQL=카탈로그 재구성 — "재구성" 라벨로 정직 표기, Mongo=컬렉션 JSON).
     * 참조 테이블이 많으면 앞 2개만 DDL로, 나머지는 인덱스 중심 요약 필드 하나로 묶는다(한도 25 방어).
     */
    private static void addSchemaFields(List<WebhookNotifier.Embed.Field> fields,
                                        ReferencedSchemaService.ReferencedSchema schema) {
        int ddlShown = 0;
        List<ReferencedSchemaService.RefTable> rest = new ArrayList<>();
        for (ReferencedSchemaService.RefTable t : schema.tables()) {
            if (ddlShown >= DDL_TABLES_MAX || !hasText(t.ddl())) {
                rest.add(t);
                continue;
            }
            StringBuilder v = new StringBuilder(ddlBlock(t.ddl()));
            String stats = ReferencedSchemaService.formatStats(t);
            if ("RECONSTRUCTED".equals(t.ddlSource())) {
                stats = stats.isEmpty() ? "DDL 재구성" : stats + " · DDL 재구성";
            }
            if (!stats.isEmpty()) {
                v.append('\n').append(stats);
            }
            String card = cardinalityLine(t);
            if (!card.isEmpty()) {
                v.append('\n').append(card);
            }
            fields.add(new WebhookNotifier.Embed.Field("관련 테이블 구조 — " + t.name(), v.toString(), false));
            ddlShown++;
        }
        if (!rest.isEmpty() || !schema.notFound().isEmpty()) {
            String compact = ReferencedSchemaService.formatCompact(
                    new ReferencedSchemaService.ReferencedSchema(rest, schema.notFound(), schema.truncated()));
            if (hasText(compact)) {
                fields.add(new WebhookNotifier.Embed.Field("관련 테이블 구조(요약)", codeBlock("", compact), false));
            }
        }
    }

    /** 인덱스 카디널리티 — DDL엔 없는 선택도 재료. 확보된 인덱스만, 앞 4개까지(필드 한도 방어). */
    private static String cardinalityLine(ReferencedSchemaService.RefTable t) {
        List<String> parts = t.indexes().stream()
                .filter(i -> i.cardinality() != null)
                .limit(4)
                .map(i -> i.name() + "≈" + String.format(Locale.ROOT, "%,d", i.cardinality()))
                .toList();
        return parts.isEmpty() ? "" : "카디널리티: " + String.join(" · ", parts);
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
