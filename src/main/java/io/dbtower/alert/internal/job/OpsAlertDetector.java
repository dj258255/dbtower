package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.AlertEmbeds;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.insight.QuerySnapshotRepository;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.DeadlockEvent;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.InstanceDeletedEvent;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final long slotRetainedBytes;

    /** key = instanceId:종류(:식별자), value = 마지막 알림 시각 */
    private final Map<String, LocalDateTime> lastAlerted = new ConcurrentHashMap<>();

    /** PG 데드락 누적 카운터의 직전 값(instanceId → count) — 폴 사이 델타로 "새 데드락"을 센다. */
    private final Map<Long, Long> lastDeadlockCount = new ConcurrentHashMap<>();

    /** MSSQL/MySQL 최근 데드락의 직전 식별 문자열(instanceId → sig) — 같은 사건 반복 알림을 막는다. */
    private final Map<Long, String> lastDeadlockSig = new ConcurrentHashMap<>();

    public OpsAlertDetector(DatabaseInstanceRepository instanceRepository,
                            DbmsOperatorFactory operatorFactory,
                            QuerySnapshotRepository snapshotRepository,
                            BackupFreshnessService backupFreshnessService,
                            WebhookNotifier notifier,
                            @Value("${dbtower.ops-alert.idle-txn-seconds:300}") int idleTxnSeconds,
                            @Value("${dbtower.ops-alert.replication-lag-seconds:30}") int replicationLagSeconds,
                            @Value("${dbtower.ops-alert.snapshot-stall-minutes:10}") int snapshotStallMinutes,
                            @Value("${dbtower.ops-alert.cooldown-minutes:30}") int cooldownMinutes,
                            @Value("${dbtower.ops-alert.slot-retained-mb:1024}") long slotRetainedMb) {
        this.instanceRepository = instanceRepository;
        this.operatorFactory = operatorFactory;
        this.snapshotRepository = snapshotRepository;
        this.backupFreshnessService = backupFreshnessService;
        this.notifier = notifier;
        this.idleTxnSeconds = idleTxnSeconds;
        this.replicationLagSeconds = replicationLagSeconds;
        this.snapshotStallMinutes = snapshotStallMinutes;
        this.cooldownMinutes = cooldownMinutes;
        this.slotRetainedBytes = slotRetainedMb * 1024 * 1024;
    }

    // HA 분산 락(Phase A5): RegressionDetector와 같은 이유로 한 시점에 한 노드만 운영 감지를 돌린다.
    // lockAtLeastFor로 같은 노드가 락을 연속으로 이기게 해서 아래 인메모리 쿨다운 맵이 그 노드에서
    // 유지되도록 한다(쿨다운의 HA 잔여 한계는 RegressionDetector 주석에 상세히 기록됨 — 동일하게 적용).
    @Scheduled(fixedDelayString = "${dbtower.ops-alert.poll-ms:120000}")
    @SchedulerLock(name = "ops-alert-detect", lockAtLeastFor = "PT110S", lockAtMostFor = "PT4M")
    public void detect() {
        LocalDateTime now = LocalDateTime.now();
        // 서버 공유 인지 (Phase 4): 같은 host:port에 등록된 DB들은 물리적으로 같은 서버라, 서버 전역
        // 신호(세션·복제·데드락)는 그룹 대표 1개에서만 탐침한다 — 중복 경보와 중복 대상 부하를 함께 줄인다.
        // id 오름차순 순회라 대표는 그룹 내 최소 id(결정적). 대표가 삭제되면 다음 폴부터 차순위가 대표가
        // 되는데, 델타 기반 데드락 감지는 첫 관측을 건너뛰므로 한 주기 놓칠 수 있다(감수 — 오탐보다 낫다).
        List<DatabaseInstance> instances = new ArrayList<>(instanceRepository.findAll());
        instances.sort(Comparator.comparing(DatabaseInstance::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, List<String>> serverGroups = new LinkedHashMap<>();
        for (DatabaseInstance i : instances) {
            if (i.isCollectionEnabled()) {
                serverGroups.computeIfAbsent(i.serverKey(), k -> new ArrayList<>()).add(i.getName());
            }
        }
        Set<String> probedServers = new HashSet<>();
        for (DatabaseInstance instance : instances) {
            // 수집 격리된 인스턴스는 능동 탐침을 멈춘다(문제 대상을 관제에서 잠시 빼는 스위치, Phase F).
            if (!instance.isCollectionEnabled()) {
                continue;
            }
            boolean serverRepresentative = probedServers.add(instance.serverKey());
            // 인스턴스 하나의 실패(접속 불가·권한 부족 등)가 다른 인스턴스 감지를 막지 않도록 개별 격리.
            List<String> findings = new ArrayList<>();
            try {
                if (serverRepresentative) {
                    // findings에 바로 누적한다 — 중간 감지 하나가 죽어도 그 전까지의 신호는 알려야 한다
                    DbmsOperator operator = operatorFactory.create(instance);
                    findings.addAll(detectIdleTransactions(instance, operator, now));
                    findings.addAll(detectReplicationLag(instance, operator, now));
                    findings.addAll(detectReplicationSlots(instance, operator, now));
                    findings.addAll(detectDeadlocks(instance, operator, now));
                    // 서버를 공유하는 다른 인스턴스가 있으면 경보가 그들에게도 해당함을 명시한다
                    // (이 시점의 findings는 전부 서버 전역 신호 — 인스턴스 스코프는 아래에서 붙는다)
                    List<String> group = serverGroups.get(instance.serverKey());
                    if (!findings.isEmpty() && group != null && group.size() > 1) {
                        findings.add("(서버 %s 공유 — 위 서버 전역 신호는 %s 전체에 해당)"
                                .formatted(instance.serverKey(), String.join(", ", group)));
                    }
                }
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
     * 복제 슬롯 잔량 (C-1, PostgreSQL) — 비활성 슬롯이 WAL을 무한 보존해 디스크를 채우는 사고를 본다.
     * pg_stat_replication의 lag는 "연결된 복제"만 보므로 이 사각을 못 잡는다. 판정:
     * - wal_status='lost' → 이미 슬롯 무효(구독자 재구축 필요), 가장 심각
     * - wal_status='unreserved' → 곧 잃기 직전 경고
     * - 비활성 슬롯이 임계 이상 WAL을 붙잡음 → 디스크 고갈 경고
     * 슬롯이 없거나 슬롯 개념이 없는 기종은 빈 결과라 자연히 스킵된다.
     */
    private List<String> detectReplicationSlots(DatabaseInstance instance, DbmsOperator operator, LocalDateTime now) {
        List<String> findings = new ArrayList<>();
        for (var slot : operator.replicationSlots()) {
            String status = slot.walStatus() == null ? "" : slot.walStatus().toLowerCase();
            String key = "slot:" + slot.slotName();
            if (status.equals("lost")) {
                if (passCooldown(key, instance, now)) {
                    findings.add("복제 슬롯 무효 slot=%s wal_status=lost — 슬롯이 필요한 WAL을 이미 잃었다(구독자 재구축 필요)"
                            .formatted(slot.slotName()));
                }
            } else if (status.equals("unreserved")) {
                if (passCooldown(key, instance, now)) {
                    findings.add("복제 슬롯 위험 slot=%s wal_status=unreserved active=%b — WAL 한계 초과 직전, 곧 무효화된다"
                            .formatted(slot.slotName(), slot.active()));
                }
            } else if (!slot.active() && slot.retainedBytes() >= slotRetainedBytes) {
                if (passCooldown(key, instance, now)) {
                    findings.add("비활성 복제 슬롯 slot=%s 보존 WAL %,dMB (구독자 끊김 — 디스크 고갈 위험)"
                            .formatted(slot.slotName(), slot.retainedBytes() / (1024 * 1024)));
                }
            }
        }
        return findings;
    }

    /**
     * 데드락 감지 (3차 아크 D-축) — 기종마다 관측 입도가 달라 두 갈래로 처리한다.
     * - PostgreSQL: 개별 사건이 없고 pg_stat_database.deadlocks 누적 카운터뿐 → 폴 사이 <b>델타</b>가
     *   0보다 크면 "새 데드락 N건". 첫 관측(직전 값 없음)이나 카운터 감소(통계 리셋)는 알리지 않는다.
     * - SQL Server / MySQL: recentDeadlocks()가 최근 리포트를 준다 → 가장 최근 사건의 식별 문자열이
     *   직전과 다르면 "새 데드락"으로 1건 알린다(같은 사건 반복 알림 방지). 롤링 저장이라 "최근"만 본다.
     * 데드락은 자체 회복(한쪽 롤백)되지만 애플리케이션 오류로 이어지므로, 반복되면 락 순서 점검 신호다.
     */
    private List<String> detectDeadlocks(DatabaseInstance instance, DbmsOperator operator, LocalDateTime now) {
        long id = instance.getId();
        Optional<Long> counter = operator.deadlockCount();
        if (counter.isPresent()) {
            long cur = counter.get();
            Long prev = lastDeadlockCount.put(id, cur);
            if (prev != null && cur > prev && passCooldown("deadlock", instance, now)) {
                return List.of("데드락 발생 +%d건 (누적 %d, pg_stat_database.deadlocks 델타) — 반복되면 락 순서 점검"
                        .formatted(cur - prev, cur));
            }
            return List.of();
        }
        List<DeadlockEvent> events = operator.recentDeadlocks(5);
        if (events.isEmpty()) {
            return List.of();
        }
        DeadlockEvent newest = events.get(0);
        String sig = newest.detectedAt() + "|" + newest.victim() + "|" + newest.resource();
        String prevSig = lastDeadlockSig.put(id, sig);
        if (!sig.equals(prevSig) && prevSig != null && passCooldown("deadlock", instance, now)) {
            String victim = newest.victim() == null ? "(victim 미상)" : newest.victim();
            return List.of("데드락 감지 (%s) victim=%s — %s"
                    .formatted(newest.source(), truncate(victim), newest.detectedAt()));
        }
        return List.of();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
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

    /**
     * 인스턴스 삭제 이벤트(B-1) — 그 인스턴스가 남긴 인메모리 상태를 전부 비운다.
     * - lastAlerted는 키가 "instanceId:종류(:식별자)"라 접두 일치로 그 인스턴스 것만 제거한다.
     * - lastDeadlockCount/lastDeadlockSig는 instanceId 키라 바로 remove.
     * 안 비우면 삭제된 인스턴스의 키가 맵에 영구 잔존하고, 같은 id가 재사용될 경우 낡은 기준선으로 오판한다.
     */
    @EventListener
    public void onInstanceDeleted(InstanceDeletedEvent event) {
        evict(event.instanceId());
    }

    void evict(long instanceId) {
        String prefix = instanceId + ":";
        lastAlerted.keySet().removeIf(k -> k.startsWith(prefix));
        lastDeadlockCount.remove(instanceId);
        lastDeadlockSig.remove(instanceId);
    }

    private void notify(DatabaseInstance instance, List<String> findings) {
        StringBuilder message = new StringBuilder();
        message.append("[DBTower 운영 경보] instance=").append(instance.getName()).append("\n");
        findings.forEach(f -> message.append("- ").append(f).append("\n"));
        log.info("운영 경보 instance={} findings={}", instance.getName(), findings.size());
        // 운영 경보는 지금-위험 신호라 빨강. 텍스트(Slack·미설정)는 폴백으로 그대로 나간다.
        notifier.sendEmbed(message.toString(), AlertEmbeds.forDetection(
                "운영 경보", AlertEmbeds.RED, instance,
                null, null, findings, null, null));
    }
}
