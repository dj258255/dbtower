package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.PlanChangeTracker;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.QueryDiff;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 쿼리 회귀 자동 감지 (확장3) — 시점 비교의 자동화 버전.
 *
 * 사람이 구간을 고르는 대신, 플랫폼이 주기적으로 "최근 구간 vs 직전 베이스라인 구간"을
 * 비교해 신규 쿼리·호출량 급증·레이턴시 회귀·읽는 행수 폭증을 잡아 웹훅으로 알린다.
 * (Datadog Query Regression Detection의 축소판 — 베이스라인은 직전 구간 하나로 단순화)
 *
 * 같은 쿼리로 알림이 반복되지 않게 쿼리별 쿨다운을 둔다.
 */
@Component
public class RegressionDetector {

    private static final Logger log = LoggerFactory.getLogger(RegressionDetector.class);

    private final DatabaseInstanceRepository instanceRepository;
    private final ComparisonService comparisonService;
    private final WebhookNotifier notifier;
    private final AiAnalyzer aiAnalyzer;

    private final int recentMinutes;
    private final int baselineMinutes;
    private final int cooldownMinutes;

    /** key = instanceId:queryId:종류, value = 마지막 알림 시각 */
    private final Map<String, LocalDateTime> lastAlerted = new ConcurrentHashMap<>();

    private final PlanChangeTracker planChangeTracker;

    public RegressionDetector(DatabaseInstanceRepository instanceRepository,
                              ComparisonService comparisonService,
                              WebhookNotifier notifier,
                              AiAnalyzer aiAnalyzer,
                              PlanChangeTracker planChangeTracker,
                              @Value("${dbtower.regression.recent-minutes:5}") int recentMinutes,
                              @Value("${dbtower.regression.baseline-minutes:15}") int baselineMinutes,
                              @Value("${dbtower.regression.cooldown-minutes:30}") int cooldownMinutes) {
        this.instanceRepository = instanceRepository;
        this.comparisonService = comparisonService;
        this.notifier = notifier;
        this.aiAnalyzer = aiAnalyzer;
        this.planChangeTracker = planChangeTracker;
        this.recentMinutes = recentMinutes;
        this.baselineMinutes = baselineMinutes;
        this.cooldownMinutes = cooldownMinutes;
    }

    // HA 분산 락(Phase A5): 한 시점에 한 노드만 회귀 감지를 돌린다.
    // lockAtLeastFor=PT110S — 120초 주기의 대부분 동안 락을 붙잡아 노드 간 드리프트로 인한 중복 감지를 막고,
    //   더 중요하게는 "같은 노드가 계속 락을 이기게" 만들어 아래 쿨다운 맵이 그 노드에서 계속 채워지도록 한다.
    // lockAtMostFor=PT4M — detect는 인스턴스별 비교(DB 조회) + AI 1차 분석(외부 호출) + 웹훅 전송이라
    //   느려질 수 있어, 정상 실행 중 다른 노드가 끼어들지 않도록 실제 소요보다 넉넉한 크래시 상한을 둔다.
    //
    // [쿨다운의 HA 잔여 한계 — 정직한 명시]
    // lastAlerted 쿨다운 맵은 여전히 "노드별 인메모리"라 노드 간 공유되지 않는다. 분산 락은 detect의
    // 동시 실행을 막을 뿐, 쿨다운 상태를 공유해 주지는 않는다. 접근 (a)를 택한 이유와 잔여 리스크:
    //   - 정상 운영에선 위 lockAtLeastFor(110s)와 fixedDelay 특성상 한 노드가 락을 연속으로 이겨
    //     그 노드의 쿨다운 맵이 계속 유지되므로, 실질 중복 알림은 크게 준다.
    //   - 그러나 락 보유 노드가 바뀌면(장애 조치·재시작·틱 타이밍 역전) 새 승자의 맵에는 쿨다운
    //     기록이 없어, 이미 알린 회귀를 쿨다운 창 안에서 한 번 더 알릴 수 있다(쿨다운 누수).
    //   - 완전 해소는 쿨다운을 메타 DB 테이블로 외부화하는 접근 (b)가 필요하다(추가 마이그레이션). 여기서는
    //     시간 대비 (a)+한계 명시를 택했고, 이 잔여 리스크는 "중복 알림 1회" 수준이라 수용 가능하다고 판단했다.
    @Scheduled(fixedDelayString = "${dbtower.regression.poll-ms:120000}")
    @SchedulerLock(name = "regression-detect", lockAtLeastFor = "PT110S", lockAtMostFor = "PT4M")
    public void detect() {
        LocalDateTime now = LocalDateTime.now();
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            try {
                ComparisonService.CompareResult result = comparisonService.compare(
                        instance.getId(),
                        now.minusMinutes((long) recentMinutes + baselineMinutes), now.minusMinutes(recentMinutes),
                        now.minusMinutes(recentMinutes), now);
                List<String> findings = evaluate(instance, result, now);
                if (!findings.isEmpty()) {
                    notify(instance, findings);
                }
            } catch (IllegalArgumentException e) {
                // 스냅샷 배치 부족 — 데이터가 쌓이면 자연히 동작한다
            } catch (Exception e) {
                log.warn("회귀 감지 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
    }

    private List<String> evaluate(DatabaseInstance instance, ComparisonService.CompareResult result,
                                  LocalDateTime now) {
        List<String> findings = new ArrayList<>();
        for (QueryDiff d : result.queries()) {
            String text = d.queryText() == null ? d.queryId() : d.queryText();
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
                findings.add("읽는 행수 폭증(플랜 변화 의심): %s (rows/call %.0f -> %.0f, %+.0f%%)"
                        .formatted(shortText, d.baseRowsPerCall(), d.targetRowsPerCall(), d.rowsPerCallChangePct()));
                planSuspect = true;
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
        LocalDateTime last = lastAlerted.get(key);
        if (last != null && last.plusMinutes(cooldownMinutes).isAfter(now)) {
            return false;
        }
        lastAlerted.put(key, now);
        return true;
    }

    private void notify(DatabaseInstance instance, List<String> findings) {
        StringBuilder message = new StringBuilder();
        message.append("[DBTower 회귀 감지] instance=").append(instance.getName())
                .append(" (최근 ").append(recentMinutes).append("분 vs 직전 ").append(baselineMinutes).append("분)\n");
        findings.forEach(f -> message.append("- ").append(f).append("\n"));

        // AI 1차 분석은 감지 묶음당 1회만 — 비용과 알림 지연을 묶어서 관리
        aiAnalyzer.analyze(message.toString())
                .ifPresent(analysis -> message.append("\nAI 1차 분석: ").append(analysis));

        log.info("회귀 감지 알림 instance={} findings={}", instance.getName(), findings.size());
        notifier.send(message.toString());
    }
}
