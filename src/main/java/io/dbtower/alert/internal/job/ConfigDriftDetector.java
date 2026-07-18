package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.AlertEmbeds;
import io.dbtower.alert.internal.ConfigDriftService;
import io.dbtower.alert.internal.ConfigDriftService.DriftResult;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.alert.internal.persistence.ConfigDriftDao;
import io.dbtower.alert.internal.persistence.ConfigDriftDao.ParamChangeRow;
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

/**
 * 설정 드리프트 감지 잡 (운영 병목 아크 B1, P1·P2). 주기적으로 각 인스턴스의 파라미터를 읽어
 * 직전 상태와 비교(ConfigDriftService)하고, 실제 변경이면 웹훅으로 알린다.
 *
 * WaitEventSnapshotJob과 같은 뼈대(수집 격리 토글 존중·인스턴스 실패 격리·ShedLock 배타·보존 스윕)를
 * 쓰되, 원천이 operator.parameters()라 신규 수집 코드가 없다. 첫 수집은 기준선이라 경보를 내지 않고
 * (P: 첫 수집 경보 폭탄 방지), 재시작 직후 대량 diff는 WebhookNotifier의 레이트리밋으로 묶인다.
 */
@Component
public class ConfigDriftDetector {

    private static final Logger log = LoggerFactory.getLogger(ConfigDriftDetector.class);

    /** 카드 한 장에 실을 변경 상한 — 나머지는 "+N건 더"로(필드 1024자 한도 방어). */
    private static final int CARD_CHANGE_LIMIT = 15;

    private final DatabaseInstanceRepository instanceRepository;
    private final ConfigDriftService driftService;
    private final ConfigDriftDao dao;
    private final WebhookNotifier notifier;
    private final int retentionDays;
    private final String baseUrl;

    public ConfigDriftDetector(DatabaseInstanceRepository instanceRepository,
                               ConfigDriftService driftService, ConfigDriftDao dao,
                               WebhookNotifier notifier,
                               @Value("${dbtower.config-drift.retention-days:365}") int retentionDays,
                               @Value("${dbtower.base-url:}") String baseUrl) {
        this.instanceRepository = instanceRepository;
        this.driftService = driftService;
        this.dao = dao;
        this.notifier = notifier;
        this.retentionDays = retentionDays;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    @Scheduled(fixedDelayString = "${dbtower.config-drift.interval-ms:3600000}")
    @SchedulerLock(name = "config-drift-collect", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void collect() {
        LocalDateTime capturedAt = LocalDateTime.now();
        int changed = 0;
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            if (!instance.isCollectionEnabled()) {
                continue;
            }
            try {
                DriftResult result = driftService.collect(instance, capturedAt);
                if (result.changed()) {
                    changed++;
                    alert(instance, result.changes());
                }
            } catch (Exception e) {
                // 한 인스턴스 실패가 나머지를 막지 않는다(WaitEventSnapshotJob과 동일 격리)
                log.warn("설정 드리프트 수집 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("설정 드리프트 감지 완료 changedInstances={}", changed);
        }
    }

    private void alert(DatabaseInstance instance, List<ParamChangeRow> changes) {
        if (!notifier.isConfigured()) {
            return;
        }
        List<String> rendered = new ArrayList<>();
        int shown = Math.min(changes.size(), CARD_CHANGE_LIMIT);
        for (int i = 0; i < shown; i++) {
            rendered.add(render(changes.get(i)));
        }
        if (changes.size() > shown) {
            rendered.add("… +" + (changes.size() - shown) + "건 더");
        }
        String deeplink = baseUrl.isBlank() ? null
                : baseUrl + "/?instance=" + instance.getId() + "&view=config-drift";
        String fallback = fallbackText(instance, changes);
        notifier.sendEmbed(fallback, instance.getId(),
                AlertEmbeds.forConfigDrift(instance, rendered, deeplink));
    }

    /** "name: old → new" — 추가는 "(없음) → new", 제거는 "old → (제거)". */
    private static String render(ParamChangeRow c) {
        return switch (c.kind()) {
            case "ADDED" -> c.paramName() + ": (없음) → " + c.newValue();
            case "REMOVED" -> c.paramName() + ": " + c.oldValue() + " → (제거)";
            default -> c.paramName() + ": " + c.oldValue() + " → " + c.newValue();
        };
    }

    private static String fallbackText(DatabaseInstance instance, List<ParamChangeRow> changes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[DBTower 설정 변경 감지] ").append(instance.getName())
                .append(" (").append(instance.getType()).append(") — ").append(changes.size()).append("건\n");
        for (ParamChangeRow c : changes) {
            sb.append("- ").append(render(c)).append('\n');
        }
        sb.append("변경 주체는 대상 DB의 감사 로그에서 확인하세요.");
        return sb.toString();
    }

    @Scheduled(fixedDelayString = "${dbtower.config-drift.retention-sweep-ms:86400000}")
    @SchedulerLock(name = "config-drift-retention-sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void sweep() {
        if (retentionDays <= 0) {
            return; // 무제한 — 운영자가 명시적으로 끈 상태
        }
        int deleted = dao.sweepOlderThan(LocalDateTime.now().minusDays(retentionDays));
        if (deleted > 0) {
            log.info("설정 드리프트 보존 정리 deleted={} retentionDays={}", deleted, retentionDays);
        }
    }
}
