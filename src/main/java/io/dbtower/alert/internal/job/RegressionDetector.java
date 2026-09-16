package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.AlertEmbeds;
import io.dbtower.alert.internal.PlanChangeTracker;
import io.dbtower.alert.AlertRaisedEvent;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.alert.internal.persistence.CooldownStore;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.AiAnalyzer.CallSite;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.QueryDiff;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.RowsMetric;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 쿼리 회귀 자동 감지 (확장3) — 시점 비교의 자동화 버전.
 *
 * 사람이 구간을 고르는 대신, 플랫폼이 주기적으로 "최근 구간 vs 직전 베이스라인 구간"을
 * 비교해 신규 쿼리·호출량 급증·레이턴시 회귀·행 지표 폭증을 잡아 웹훅으로 알린다.
 * (Datadog Query Regression Detection의 축소판 — 베이스라인은 직전 구간 하나로 단순화)
 *
 * 같은 쿼리로 알림이 반복되지 않게 쿼리별 쿨다운을 둔다.
 */
@Component
public class RegressionDetector {

    private static final Logger log = LoggerFactory.getLogger(RegressionDetector.class);

    private final RegistryService registryService;
    private final ComparisonService comparisonService;
    private final WebhookNotifier notifier;
    private final AiAnalyzer aiAnalyzer;
    private final QueryMasker queryMasker;
    private final String baseUrl;

    private final int recentMinutes;
    private final int baselineMinutes;

    /**
     * 쿼리별 쿨다운 — key = instanceId:queryId:종류. <b>전송이 실제로 성공해야 확정한다</b>(CooldownGate 규율).
     * 예전에는 이 감지기가 같은 맵·pending을 따로 들고 있었다 — 운영 경보와 같은 게이트로 모아 저장소를 한 번에 바꾼다.
     */
    private final CooldownGate cooldown;


    private final PlanChangeTracker planChangeTracker;
    private final DbmsOperatorFactory operators;

    // 전송에 성공한 경보를 다른 모듈에 알린다. 생성자가 아니라 주입 메서드로 받는 이유: 테스트가 생성자를 직접 부르고,
    // 이벤트를 듣는 쪽이 없어도 감지는 그대로 돌아야 해서 기본값을 아무것도 안 하는 발행기로 둔다
    private ApplicationEventPublisher events = event -> { };

    @Autowired
    void setEvents(ApplicationEventPublisher events) {
        this.events = events;
    }

    // 쿨다운 저장소도 같은 이유로 세터로 받는다 — 테스트는 인메모리 기본값으로 규칙만 보고, 운영은 메타 DB에 둔다
    @Autowired
    void setCooldownStore(CooldownStore store) {
        cooldown.attach(store);
    }

    public RegressionDetector(RegistryService registryService,
                              ComparisonService comparisonService,
                              WebhookNotifier notifier,
                              AiAnalyzer aiAnalyzer,
                              QueryMasker queryMasker,
                              PlanChangeTracker planChangeTracker,
                              DbmsOperatorFactory operators,
                              @Value("${dbtower.regression.recent-minutes:5}") int recentMinutes,
                              @Value("${dbtower.regression.baseline-minutes:15}") int baselineMinutes,
                              @Value("${dbtower.regression.cooldown-minutes:30}") int cooldownMinutes,
                              @Value("${dbtower.base-url:}") String baseUrl) {
        this.registryService = registryService;
        this.comparisonService = comparisonService;
        this.notifier = notifier;
        this.aiAnalyzer = aiAnalyzer;
        this.queryMasker = queryMasker;
        this.planChangeTracker = planChangeTracker;
        this.operators = operators;
        this.recentMinutes = recentMinutes;
        this.baselineMinutes = baselineMinutes;
        this.cooldown = new CooldownGate(cooldownMinutes, "regression");
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    // HA 분산 락(Phase A5): 한 시점에 한 노드만 회귀 감지를 돌린다.
    // lockAtLeastFor=PT110S — 120초 주기의 대부분 동안 락을 붙잡아 노드 간 드리프트로 인한 중복 감지를 막고,
    //   더 중요하게는 "같은 노드가 계속 락을 이기게" 만들어 아래 쿨다운 맵이 그 노드에서 계속 채워지도록 한다.
    // lockAtMostFor=PT4M — detect는 인스턴스별 비교(DB 조회) + AI 1차 분석(외부 호출) + 웹훅 전송이라
    //   느려질 수 있어, 정상 실행 중 다른 노드가 끼어들지 않도록 실제 소요보다 넉넉한 크래시 상한을 둔다.
    //
    // [쿨다운의 HA — 접근 (b)로 옮겼다]
    // 예전에는 쿨다운 맵이 노드별 인메모리였고(접근 (a)), 락 보유 노드가 바뀌거나 재기동하면 이미 알린 회귀를
    // 쿨다운 창 안에서 한 번 더 알렸다. 그때는 "중복 알림 1회" 수준이라 수용했다. 경보가 AI 운영 작업을 만들게 된 뒤로는
    // 그 1회가 모델 호출이 되어 전제가 바뀌었다 — 170절에서 재기동 직후 같은 신규 쿼리 경보가 다시 나고 작업까지
    // 다시 만들어졌다. 그래서 쿨다운을 메타 DB(alert_cooldown, V45)로 외부화했다. 분산 락은 여전히 동시 실행을 막고,
    // 쿨다운 상태는 이제 노드와 재기동을 넘어 이어진다.
    @Scheduled(fixedDelayString = "${dbtower.regression.poll-ms:120000}")
    @SchedulerLock(name = "regression-detect", lockAtLeastFor = "PT110S", lockAtMostFor = "PT4M")
    public void detect() {
        LocalDateTime now = LocalDateTime.now();
        for (DatabaseInstance instance : registryService.findAll()) {
            try {
                ComparisonService.CompareResult result = comparisonService.compare(
                        instance.getId(),
                        now.minusMinutes((long) recentMinutes + baselineMinutes), now.minusMinutes(recentMinutes),
                        now.minusMinutes(recentMinutes), now);
                List<String> findings = evaluate(instance, result, now);
                if (!findings.isEmpty()) {
                    if (notify(instance, findings)) {
                        cooldown.commit(now);
                        events.publishEvent(new AlertRaisedEvent(AlertRaisedEvent.Source.REGRESSION, instance.getId(),
                                instance.getName(), findings, recentMinutes + baselineMinutes, now));
                    } else {
                        // 전송 실패·레이트리밋 — 쿨다운 미확정. 다음 폴에서 다시 감지해 재시도한다.
                        cooldown.clearPending();
                        log.warn("회귀 감지 알림 전송 실패 instance={} — 쿨다운 미확정", instance.getName());
                    }
                }
            } catch (IllegalArgumentException e) {
                // 스냅샷 배치 부족 — 데이터가 쌓이면 자연히 동작한다
            } catch (Exception e) {
                log.warn("회귀 감지 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
    }

    /** 행 지표의 뜻은 기종 오퍼레이터가 안다. 오퍼레이터를 못 만들면(드라이버·설정 문제) 검사량 지표로 보고 기존 판정을 유지한다. */
    private RowsMetric rowsMetric(DatabaseInstance instance) {
        try {
            return operators.create(instance).rowsMetric();
        } catch (RuntimeException e) {
            log.debug("행 지표 확인 실패 instance={} cause={}", instance.getName(), e.getMessage());
            return RowsMetric.EXAMINED_ROWS;
        }
    }

    private List<String> evaluate(DatabaseInstance instance, ComparisonService.CompareResult result,
                                  LocalDateTime now) {
        List<String> findings = new ArrayList<>();
        RowsMetric metric = rowsMetric(instance);
        for (QueryDiff d : result.queries()) {
            // 외부(웹훅)로 나가는 유일한 SQL 지점 — 리터럴을 가린다. MySQL/PG 정규화 텍스트에는
            // 멱등이고, Oracle(V$SQL 원문)·Mongo(명령 JSON)의 실값이 실제 보호 대상이다.
            String text = d.queryText() == null ? d.queryId() : queryMasker.apply(d.queryText());
            String shortText = text.length() > 90 ? text.substring(0, 90) + "..." : text;

            if (d.newQuery() && d.targetQps() >= 0.1 && underCooldown(instance, d, "new", now)) {
                findings.add("신규 쿼리 유입: %s (QPS %.2f, rows/call %.0f)"
                        .formatted(shortText, d.targetQps(), d.targetRowsPerCall()));
            }
            if (d.qpsChangePct() != null && d.qpsChangePct() >= 200 && d.targetQps() >= 0.5
                    && underCooldown(instance, d, "qps", now)) {
                findings.add("호출량 급증: %s (QPS %.2f -> %.2f, %+.0f%%)"
                        .formatted(shortText, d.baseQps(), d.targetQps(), d.qpsChangePct()));
            }
            boolean planSuspect = false;
            if (d.latencyChangePct() != null && d.latencyChangePct() >= 200 && d.targetAvgMs() >= 1
                    && underCooldown(instance, d, "latency", now)) {
                findings.add("레이턴시 회귀: %s (평균 %.2f -> %.2fms, %+.0f%%)"
                        .formatted(shortText, d.baseAvgMs(), d.targetAvgMs(), d.latencyChangePct()));
                planSuspect = true;
            }
            if (d.rowsPerCallChangePct() != null && d.rowsPerCallChangePct() >= 500 && d.targetRowsPerCall() >= 100
                    && underCooldown(instance, d, "rows", now)) {
                // 행 지표가 "돌려주거나 바꾼 행"인 기종(PostgreSQL)에서 이 급증은 결과 크기 변화지 스캔량 변화가 아니다 —
                // 플랜 변화로 적거나 추정 explain을 뜨지 않는다(132절)
                boolean scanWork = metric != RowsMetric.RETURNED_ROWS;
                findings.add("%s 폭증(%s): %s (rows/call %.0f -> %.0f, %+.0f%%)"
                        .formatted(metric.label(), scanWork ? "플랜 변화 의심" : "결과 크기 변화, 스캔량 지표 아님",
                                shortText, d.baseRowsPerCall(), d.targetRowsPerCall(), d.rowsPerCallChangePct()));
                planSuspect = planSuspect || scanWork;
            }
            // 플랜 변경(plan flip) 확인 — "느려졌다"에서 "계획이 갈아탔다"까지. 회귀가 감지된
            // 쿼리만 추정 explain을 뜨므로 실행 부하 없음(A9). 첫 관측은 기준선이라 조용하다.
            if (planSuspect) {
                planChangeTracker.check(instance, d.queryId(), d.queryText()).ifPresent(pc ->
                        findings.add("실행계획 변경 확인: %s — %s  ->  %s"
                                .formatted(shortText, pc.fromShape(), pc.toShape())));
            }
        }
        return findings;
    }

    private boolean underCooldown(DatabaseInstance instance, QueryDiff d, String kind, LocalDateTime now) {
        String key = instance.getId() + ":" + d.queryId() + ":" + kind;
        return cooldown.pass(key, now);   // 확정은 전송 성공 후(cooldown.commit)
    }


    private boolean notify(DatabaseInstance instance, List<String> findings) {
        StringBuilder message = new StringBuilder();
        message.append("[DBTower 회귀 감지] instance=").append(instance.getName())
                .append(" (최근 ").append(recentMinutes).append("분 vs 직전 ").append(baselineMinutes).append("분)\n");
        // 담당 팀/콘솔 링크 — "어느 팀 채널로 갈 문제인가"를 알림 자체가 말하게 한다(심화 아크 4)
        if (instance.getTeamLabel() != null && !instance.getTeamLabel().isBlank()) {
            message.append("담당: ").append(instance.getTeamLabel()).append("\n");
        }
        if (instance.getConsoleUrl() != null && !instance.getConsoleUrl().isBlank()) {
            message.append("콘솔: ").append(instance.getConsoleUrl()).append("\n");
        }
        findings.forEach(f -> message.append("- ").append(f).append("\n"));

        // AI 1차 분석은 감지 묶음당 1회만 — 비용과 알림 지연을 묶어서 관리
        String analysis = aiAnalyzer.analyze(CallSite.REGRESSION, message.toString()).orElse(null);
        if (analysis != null) {
            message.append("\nAI 1차 분석: ").append(analysis);
        }

        // 진단 딥링크 (심화 아크 5) — 레퍼런스의 "알럿 쓰레드에서 분석"을 셀프호스트 제약에 맞게:
        // 클릭 한 번으로 콘솔이 해당 인스턴스 + 자연어 진단 질문 프리필 상태로 열린다
        String deeplink = null;
        if (!baseUrl.isBlank()) {
            // 질문에 회귀 라인 전문을 넣으면 URL이 수백 자로 길어져 Discord가 마스킹 링크로 렌더하지 못한다
            // (콘솔은 이미 해당 인스턴스로 열리고 알림에 내용이 있으니, 프리필은 짧은 지시로 충분).
            String question = URLEncoder.encode(
                    "방금 온 회귀 알림의 원인을 분석해줘", StandardCharsets.UTF_8);
            deeplink = baseUrl + "/?instance=" + instance.getId() + "&diagnose=" + question;
            message.append("\n진단: ").append(deeplink);
        }

        log.info("회귀 감지 알림 instance={} findings={}", instance.getName(), findings.size());
        // 리치 embed(문의 카드와 같은 결) — 회귀는 성능 신호라 앰버. Slack·미설정은 텍스트 폴백.
        return notifier.sendEmbed(message.toString(), instance.getId(), AlertEmbeds.forDetection(
                "회귀 감지", AlertEmbeds.AMBER, instance,
                "구간", "최근 " + recentMinutes + "분 vs 직전 " + baselineMinutes + "분",
                findings, analysis, deeplink));
    }
}
