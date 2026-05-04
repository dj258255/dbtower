package io.dbtower.alert;

import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.QueryDiff;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
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

    public RegressionDetector(DatabaseInstanceRepository instanceRepository,
                              ComparisonService comparisonService,
                              WebhookNotifier notifier,
                              AiAnalyzer aiAnalyzer,
                              @Value("${dbtower.regression.recent-minutes:5}") int recentMinutes,
                              @Value("${dbtower.regression.baseline-minutes:15}") int baselineMinutes,
                              @Value("${dbtower.regression.cooldown-minutes:30}") int cooldownMinutes) {
        this.instanceRepository = instanceRepository;
        this.comparisonService = comparisonService;
        this.notifier = notifier;
        this.aiAnalyzer = aiAnalyzer;
        this.recentMinutes = recentMinutes;
        this.baselineMinutes = baselineMinutes;
        this.cooldownMinutes = cooldownMinutes;
    }

    @Scheduled(fixedDelayString = "${dbtower.regression.poll-ms:120000}")
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
            if (d.latencyChangePct() != null && d.latencyChangePct() >= 200 && d.targetAvgMs() >= 1
                    && underCooldown(instance, d, "latency", now)) {
                findings.add("레이턴시 회귀: %s (평균 %.2f -> %.2fms, %+.0f%%)"
                        .formatted(shortText, d.baseAvgMs(), d.targetAvgMs(), d.latencyChangePct()));
            }
            if (d.rowsPerCallChangePct() != null && d.rowsPerCallChangePct() >= 500 && d.targetRowsPerCall() >= 100
                    && underCooldown(instance, d, "rows", now)) {
                findings.add("읽는 행수 폭증(플랜 변화 의심): %s (rows/call %.0f -> %.0f, %+.0f%%)"
                        .formatted(shortText, d.baseRowsPerCall(), d.targetRowsPerCall(), d.rowsPerCallChangePct()));
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
