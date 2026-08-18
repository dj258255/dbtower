package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.AlertEmbeds;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.insight.BaselineService;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
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

    private final RegistryService registryService;
    private final BaselineService baselineService;
    private final WebhookNotifier notifier;

    private final int cooldownMinutes;

    /** key = instanceId:queryId, value = 마지막 알림 시각. RegressionDetector와 같은 인메모리 쿨다운(HA 한계도 동일) */
    private final Map<String, LocalDateTime> lastAlerted = new ConcurrentHashMap<>();

    /**
     * 이번 인스턴스 패스에서 쿨다운을 통과한 키 — <b>전송이 실제로 성공해야 확정한다.</b>
     * 예전에는 판정과 동시에 확정해서, 웹훅이 잠깐 죽거나 레이트리밋에 걸린 순간의 경보가
     * 쿨다운 때문에 재감지조차 되지 않고 영구히 사라졌다. 폴러는 ShedLock으로 단일 흐름이다.
     */
    private final java.util.List<String> pendingCooldown = new java.util.ArrayList<>();


    public AnomalyDetector(RegistryService registryService,
                           BaselineService baselineService,
                           WebhookNotifier notifier,
                           @Value("${dbtower.baseline.cooldown-minutes:30}") int cooldownMinutes) {
        this.registryService = registryService;
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
        for (DatabaseInstance instance : registryService.findAll()) {
            try {
                BaselineService.AnomalyScan scan = baselineService.detectAnomalies(instance.getId(), now);
                if (!scan.anomalies().isEmpty()) {
                    if (notify(instance, scan, now)) {
                        commitCooldown(now);
                    } else {
                        // 전송 실패·레이트리밋 — 쿨다운 미확정. 다음 폴에서 다시 감지해 재시도한다.
                        pendingCooldown.clear();
                        log.warn("이상 감지 알림 전송 실패 instance={} — 쿨다운 미확정", instance.getName());
                    }
                }
            } catch (Exception e) {
                // 한 인스턴스 실패가 나머지 감지를 막으면 안 된다
                log.warn("베이스라인 이상 감지 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
    }

    private boolean notify(DatabaseInstance instance, BaselineService.AnomalyScan scan, LocalDateTime now) {
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
            return true; // 전부 쿨다운 중 — 보낼 것이 없으니 재시도 대상도 아니다
        }

        String context = scan.dayOfWeek() + "요일 " + scan.hour() + "시대 평소 기준, z>=" + scan.zThreshold();
        StringBuilder message = new StringBuilder();
        message.append("[DBTower 이상 감지(베이스라인)] instance=").append(instance.getName())
                .append(" (").append(context).append(")\n");
        lines.forEach(l -> message.append("- ").append(l).append("\n"));

        log.info("베이스라인 이상 감지 알림 instance={} anomalies={}", instance.getName(), lines.size());
        // 이상 감지는 "평소와 다름" 신호라 보라. 베이스라인 맥락을 맥락 필드로 싣는다.
        return notifier.sendEmbed(message.toString(), instance.getId(), AlertEmbeds.forDetection(
                "이상 감지", AlertEmbeds.PURPLE, instance,
                "베이스라인", context, lines, null, null));
    }

    private boolean underCooldown(Long instanceId, String queryId, LocalDateTime now) {
        String key = instanceId + ":" + queryId;
        LocalDateTime last = lastAlerted.get(key);
        if (last != null && last.plusMinutes(cooldownMinutes).isAfter(now)) {
            return false;
        }
        pendingCooldown.add(key);   // 확정은 전송 성공 후(commitCooldown)
        return true;
    }

    /** 전송 성공 — 이번 패스에서 통과한 키들의 쿨다운을 그때 확정한다. */
    private void commitCooldown(java.time.LocalDateTime now) {
        pendingCooldown.forEach(k -> lastAlerted.put(k, now));
        pendingCooldown.clear();
    }
}
