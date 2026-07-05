package io.dbtower.alert;

import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.insight.QuerySnapshotRepository;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.ReplicationState;
import io.dbtower.operator.SessionInfo;
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
 * 운영 신호 자동 감지 (Phase B5) — 쿼리 성능 회귀(RegressionDetector) 바깥의 "운영 이상 징후"를
 * 폴러가 잡아 웹훅으로 알린다.
 *
 * RegressionDetector가 "쿼리가 느려졌나"라면, 이쪽은 "DB가 위험한 상태에 있나"를 본다:
 * 장기 유휴 트랜잭션(락·VACUUM 차단), 복제 지연(읽기 정합성·페일오버 위험), 스냅샷 수집 정지
 * (관제 자체가 눈이 먼 상태), 백업 신선도 초과(D7 — 마지막 백업이 임계보다 오래됨). 새 operator
 * 메서드를 만들지 않고 기존 진단 메서드(activeSessions·replicationState)와 메타 DB
 * (QuerySnapshotRepository·BackupFreshnessService)만 재사용한다.
 *
 * 같은 신호로 알림이 반복되지 않게 신호별 쿨다운을 둔다(RegressionDetector와 동일 방식).
 */
@Component
public class OpsAlertDetector {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertDetector.class);

    /** activeSessions 조회 상한 — 장기 유휴 세션은 소수라 이 정도면 충분하다(elapsed 내림차순이라 앞쪽에 몰린다) */
    private static final int SESSION_SCAN_LIMIT = 200;

    private final DatabaseInstanceRepository instanceRepository;
    private final DbmsOperatorFactory operatorFactory;
    private final QuerySnapshotRepository snapshotRepository;
    private final BackupFreshnessService backupFreshnessService;
    private final WebhookNotifier notifier;

    private final int idleTxnSeconds;
    private final int replicationLagSeconds;
    private final int snapshotStallMinutes;
    private final int cooldownMinutes;

    /** key = instanceId:종류(:식별자), value = 마지막 알림 시각 */
    private final Map<String, LocalDateTime> lastAlerted = new ConcurrentHashMap<>();

    public OpsAlertDetector(DatabaseInstanceRepository instanceRepository,
                            DbmsOperatorFactory operatorFactory,
                            QuerySnapshotRepository snapshotRepository,
                            BackupFreshnessService backupFreshnessService,
                            WebhookNotifier notifier,
                            @Value("${dbtower.ops-alert.idle-txn-seconds:300}") int idleTxnSeconds,
                            @Value("${dbtower.ops-alert.replication-lag-seconds:30}") int replicationLagSeconds,
                            @Value("${dbtower.ops-alert.snapshot-stall-minutes:10}") int snapshotStallMinutes,
                            @Value("${dbtower.ops-alert.cooldown-minutes:30}") int cooldownMinutes) {
        this.instanceRepository = instanceRepository;
        this.operatorFactory = operatorFactory;
        this.snapshotRepository = snapshotRepository;
        this.backupFreshnessService = backupFreshnessService;
        this.notifier = notifier;
        this.idleTxnSeconds = idleTxnSeconds;
        this.replicationLagSeconds = replicationLagSeconds;
        this.snapshotStallMinutes = snapshotStallMinutes;
        this.cooldownMinutes = cooldownMinutes;
    }

    // HA 분산 락(Phase A5): RegressionDetector와 같은 이유로 한 시점에 한 노드만 운영 감지를 돌린다.
    // lockAtLeastFor로 같은 노드가 락을 연속으로 이기게 해서 아래 인메모리 쿨다운 맵이 그 노드에서
    // 유지되도록 한다(쿨다운의 HA 잔여 한계는 RegressionDetector 주석에 상세히 기록됨 — 동일하게 적용).
    @Scheduled(fixedDelayString = "${dbtower.ops-alert.poll-ms:120000}")
    @SchedulerLock(name = "ops-alert-detect", lockAtLeastFor = "PT110S", lockAtMostFor = "PT4M")
    public void detect() {
        LocalDateTime now = LocalDateTime.now();
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            // 인스턴스 하나의 실패(접속 불가·권한 부족 등)가 다른 인스턴스 감지를 막지 않도록 개별 격리.
            List<String> findings = new ArrayList<>();
            try {
                DbmsOperator operator = operatorFactory.create(instance);
                findings.addAll(detectIdleTransactions(instance, operator, now));
                findings.addAll(detectReplicationLag(instance, operator, now));
                findings.addAll(detectSnapshotStall(instance, now));
            } catch (Exception e) {
                // 폴러가 죽으면 안 된다 — 감지 실패는 로그로만 남기고 넘어간다
                log.warn("운영 감지 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
            // 백업 신선도(D7)는 대상 접속과 무관한 메타 DB 판정이라 별도 try로 분리한다.
            // 위의 operator 기반 감지가 접속 실패로 죽어도 백업 경보는 계속 울려야 한다
            // (대상이 죽어 있을 때가 "백업이 최신인가"가 가장 중요한 순간이다).
            try {
                findings.addAll(detectStaleBackup(instance, now));
            } catch (Exception e) {
                log.warn("백업 신선도 감지 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
            if (!findings.isEmpty()) {
                notify(instance, findings);
            }
        }
    }

    /**
     * 장기 유휴 트랜잭션 — state가 'idle in transaction'류이고 경과가 임계를 넘으면 알린다.
     * idle in transaction 세션은 트랜잭션을 연 채 아무 일도 안 하면서 락과 오래된 스냅샷 수평선을
     * 붙잡아 VACUUM을 막고 다른 세션을 대기시키는, 운영에서 가장 흔한 사고 원인이다.
     * 쿨다운은 pid 단위로 둔다 — 한 세션이 오래 걸려 있다고 다른 세션의 알림까지 묻히면 안 된다.
     */
    private List<String> detectIdleTransactions(DatabaseInstance instance, DbmsOperator operator, LocalDateTime now) {
        List<String> findings = new ArrayList<>();
        long thresholdMs = idleTxnSeconds * 1000L;
        for (SessionInfo s : operator.activeSessions(SESSION_SCAN_LIMIT)) {
            String state = s.state();
            if (state == null || !state.toLowerCase().contains("idle in transaction")) {
                continue;
            }
            if (s.elapsedMs() <= thresholdMs) {
                continue;
            }
            if (!passCooldown("idle-txn:" + s.pid(), instance, now)) {
                continue;
            }
            findings.add("장기 유휴 트랜잭션 pid=%d user=%s state='%s' %.0fs (락·VACUUM 차단 위험)"
                    .formatted(s.pid(), s.user(), state, s.elapsedMs() / 1000.0));
        }
        return findings;
    }

    /**
     * 복제 지연 — lagSeconds가 임계를 넘으면 알린다.
     * STANDALONE(복제 미구성)과 lag 음수(-1: 미지원/미상)는 판단 근거가 아니라 스킵한다.
     */
    private List<String> detectReplicationLag(DatabaseInstance instance, DbmsOperator operator, LocalDateTime now) {
        ReplicationState state = operator.replicationState();
        if (state == null || "STANDALONE".equalsIgnoreCase(state.role()) || state.lagSeconds() < 0) {
            return List.of();
        }
        if (state.lagSeconds() <= replicationLagSeconds) {
            return List.of();
        }
        if (!passCooldown("replication", instance, now)) {
            return List.of();
        }
        return List.of("복제 지연 role=%s lag=%.1fs (임계 %ds 초과, %s)"
                .formatted(state.role(), state.lagSeconds(), replicationLagSeconds, state.detail()));
    }

    /**
     * 스냅샷 수집 정지 (선택 규칙) — 최근 stall 창 안에 이 인스턴스의 신규 스냅샷 배치가 0이면 알린다.
     * 수집이 멈추면 회귀·시점 비교·활동 그래프가 전부 눈이 먼 상태가 되므로, "관제가 관제되고 있나"를 본다.
     *
     * 범위: 방금 등록돼 아직 첫 수집 전인 인스턴스는 오탐이라, createdAt이 stall 창보다 오래된
     * 인스턴스만 대상으로 한다. 급증 등 세밀한 적재 추세 판정은 노이즈 대비 실익이 낮아 이번 범위에서 제외.
     */
    private List<String> detectSnapshotStall(DatabaseInstance instance, LocalDateTime now) {
        LocalDateTime windowStart = now.minusMinutes(snapshotStallMinutes);
        if (instance.getCreatedAt() != null && instance.getCreatedAt().isAfter(windowStart)) {
            return List.of(); // 등록 직후 — 아직 수집 전이라 판단 보류
        }
        boolean hasRecent = !snapshotRepository.sumByBatch(instance.getId(), windowStart, now).isEmpty();
        if (hasRecent || !passCooldown("snapshot-stall", instance, now)) {
            return List.of();
        }
        return List.of("스냅샷 수집 정지 — 최근 %d분간 신규 배치 0 (수집기 중단·접속 실패 의심, 회귀 감지 무력화)"
                .formatted(snapshotStallMinutes));
    }

    /**
     * 백업 신선도 초과 (Phase D7) — 마지막 성공 백업이 임계(freshness-hours)보다 오래됐으면 알린다.
     * "백업했다"가 아니라 "지금 백업이 최신인가"를 상시 감시하는 DBA 일일 점검을 폴러로 옮긴 것.
     *
     * 판정 자체는 BackupFreshnessService(읽기·집계)에 있고, 여기서는 알림 정책만 정한다:
     * - STALE(임계 초과): 항상 알린다. 마지막 백업의 복원 검증이 FAILED면 근거에 덧붙인다(복원 못 하는 백업은 백업이 아니다).
     * - NO_BACKUP(성공 이력 없음): 사각지대라 알리되, 등록 직후라 아직 첫 백업 전인 인스턴스는 오탐이므로
     *   createdAt이 임계 창보다 오래된 경우에만(스냅샷 정지 규칙과 같은 신규 오탐 방지).
     * FRESH는 조용하다. 쿨다운 키는 신호 단위(backup-freshness) — 다른 운영 신호와 독립.
     */
    private List<String> detectStaleBackup(DatabaseInstance instance, LocalDateTime now) {
        BackupFreshness f = backupFreshnessService.freshnessFor(instance);
        if (f.status() == BackupFreshness.Status.FRESH) {
            return List.of();
        }
        if (f.status() == BackupFreshness.Status.NO_BACKUP) {
            LocalDateTime windowStart = now.minusHours(f.thresholdHours());
            if (instance.getCreatedAt() != null && instance.getCreatedAt().isAfter(windowStart)) {
                return List.of(); // 등록 직후 — 아직 첫 백업 전이라 판단 보류
            }
            if (!passCooldown("backup-freshness", instance, now)) {
                return List.of();
            }
            return List.of("백업 없음 — 성공한 백업 이력이 없습니다 (임계 %dh, 3-2-1 사각지대)"
                    .formatted(f.thresholdHours()));
        }
        // STALE
        if (!passCooldown("backup-freshness", instance, now)) {
            return List.of();
        }
        String verifyNote = "FAILED".equalsIgnoreCase(f.verifyStatus()) ? ", 복원 검증 FAILED" : "";
        return List.of("백업 신선도 초과 — 마지막 성공 백업 %.1fh 전 (임계 %dh 초과%s)"
                .formatted(f.elapsedHours(), f.thresholdHours(), verifyNote));
    }

    /** RegressionDetector와 동일한 쿨다운 — 같은 신호는 cooldownMinutes 안에 한 번만 알린다 */
    private boolean passCooldown(String kind, DatabaseInstance instance, LocalDateTime now) {
        String key = instance.getId() + ":" + kind;
        LocalDateTime last = lastAlerted.get(key);
        if (last != null && last.plusMinutes(cooldownMinutes).isAfter(now)) {
            return false;
        }
        lastAlerted.put(key, now);
        return true;
    }

    private void notify(DatabaseInstance instance, List<String> findings) {
        StringBuilder message = new StringBuilder();
        message.append("[DBTower 운영 경보] instance=").append(instance.getName()).append("\n");
        findings.forEach(f -> message.append("- ").append(f).append("\n"));
        log.info("운영 경보 instance={} findings={}", instance.getName(), findings.size());
        notifier.send(message.toString());
    }
}
