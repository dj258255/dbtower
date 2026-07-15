package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.insight.BaselineService;
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
 * 베이스라인 이상 자동 감지 폴러 (Phase D1) — RegressionDetector의 "평소 대비" 동반자.
 *
 * RegressionDetector는 "직전 구간 대비 +200%" 고정 임계로 급격한 회귀를 잡는다. 이 폴러는 그와 공존하며,
 * BaselineService가 학습한 (요일×시간대) 베이스라인에서 z-score로 벗어난 쿼리를 "평소 대비 이탈"이라는
 * 추가 신호로 알린다. 주기적 부하(매일 아침 배치 등)를 회귀로 오인하지 않고, 임계 밑에서 서서히 무거워지는
 * 저하도 분포 이탈로 잡는다.
 *
 * 판정 로직은 전부 BaselineService에 있고, 여기서는 폴링·쿨다운·알림만 한다(RegressionDetector와 동일 골격).
 */
@Component
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    private final DatabaseInstanceRepository instanceRepository;
    private final BaselineService baselineService;
    private final WebhookNotifier notifier;

    private final int cooldownMinutes;

    /** key = instanceId:queryId, value = 마지막 알림 시각. RegressionDetector와 같은 인메모리 쿨다운(HA 한계도 동일) */
    private final Map<String, LocalDateTime> lastAlerted = new ConcurrentHashMap<>();

    public AnomalyDetector(DatabaseInstanceRepository instanceRepository,
                           BaselineService baselineService,
                           WebhookNotifier notifier,
                           @Value("${dbtower.baseline.cooldown-minutes:30}") int cooldownMinutes) {
        this.instanceRepository = instanceRepository;
        this.baselineService = baselineService;
        this.notifier = notifier;
        this.cooldownMinutes = cooldownMinutes;
    }

    // HA 분산 락(Phase A5): SnapshotScheduler·RegressionDetector와 같은 이유로 한 시점에 한 노드만 돈다.
    // 쿨다운 맵이 노드별 인메모리인 잔여 한계는 RegressionDetector 주석과 동일하다(중복 알림 최대 1회, 수용 가능).
    @Scheduled(fixedDelayString = "${dbtower.baseline.poll-ms:120000}")
    @SchedulerLock(name = "baseline-anomaly-detect", lockAtLeastFor = "PT110S", lockAtMostFor = "PT4M")
    public void detect() {
        LocalDateTime now = LocalDateTime.now();
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            try {
                BaselineService.AnomalyScan scan = baselineService.detectAnomalies(instance.getId(), now);
                if (!scan.anomalies().isEmpty()) {
                    notify(instance, scan, now);
                }
            } catch (Exception e) {
                // 한 인스턴스 실패가 나머지 감지를 막으면 안 된다
                log.warn("베이스라인 이상 감지 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
    }

    private void notify(DatabaseInstance instance, BaselineService.AnomalyScan scan, LocalDateTime now) {
        List<String> lines = new ArrayList<>();
        for (BaselineService.QueryAnomaly q : scan.anomalies()) {
            if (!underCooldown(instance.getId(), q.queryId(), now)) {
                continue;
            }
            String text = q.queryText() == null ? q.queryId() : q.queryText();
            String shortText = text.length() > 90 ? text.substring(0, 90) + "..." : text;
            List<String> metrics = new ArrayList<>();
            for (BaselineService.MetricAnomaly m : q.anomalies()) {
                metrics.add("%s %.2f (평소 %.2f±%.2f, z=%.1f)"
                        .formatted(m.metric(), m.current(), m.baselineMean(), m.baselineStddev(), m.zScore()));
            }
            lines.add("평소 대비 이탈: %s [%s] (관측 %d회)"
                    .formatted(shortText, String.join(", ", metrics), q.observations()));
        }
        if (lines.isEmpty()) {
            return; // 전부 쿨다운 중
        }

        StringBuilder message = new StringBuilder();
        message.append("[DBTower 이상 감지(베이스라인)] instance=").append(instance.getName())
                .append(" (").append(scan.dayOfWeek()).append("요일 ").append(scan.hour())
                .append("시대 평소 기준, z>=").append(scan.zThreshold()).append(")\n");
        lines.forEach(l -> message.append("- ").append(l).append("\n"));

        log.info("베이스라인 이상 감지 알림 instance={} anomalies={}", instance.getName(), lines.size());
        notifier.send(message.toString());
    }

    private boolean underCooldown(Long instanceId, String queryId, LocalDateTime now) {
        String key = instanceId + ":" + queryId;
        LocalDateTime last = lastAlerted.get(key);
        if (last != null && last.plusMinutes(cooldownMinutes).isAfter(now)) {
            return false;
        }
        lastAlerted.put(key, now);
        return true;
    }
}
