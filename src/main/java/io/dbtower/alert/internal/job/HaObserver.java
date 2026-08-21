package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.AlertEmbeds;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.registry.DatabaseInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HA 관측 — failover(역할 변경)와 split-brain을 감지한다. DBTower는 failover를 <b>실행</b>하지 않고
 * <b>관측</b>만 한다(승격·펜싱·전환은 정족수·펜싱을 가진 클러스터 매니저 몫). 대상 DB에 아무것도 쓰지
 * 않고 {@code replicationState()}의 역할만 읽는다. 감지기(관제)와 실행기(failover)가 같은 단일 지점이면
 * 그것이 죽을 때 둘 다 잃기 때문이다. 쿨다운은 {@link CooldownGate}를 OpsAlertDetector와 공유하고,
 * 역할 기준선 전진은 전송 성공({@link #commitPendingRole}) 후에만 한다(웹훅 실패 시 failover 경보 소실 방지).
 */
class HaObserver {

    private static final Logger log = LoggerFactory.getLogger(HaObserver.class);

    private final WebhookNotifier notifier;
    private final CooldownGate cooldown;

    /** 인스턴스별 직전 복제 역할 — 폴 사이에 STANDBY↔WRITABLE로 뒤집히면 failover 신호. */
    private final Map<Long, HaRole> lastRole = new ConcurrentHashMap<>();

    /**
     * 이번 인스턴스 패스에서 감지한 역할 변경의 새 역할(instanceId → cur). <b>전송이 성공해야 기준선
     * (lastRole)을 전진시킨다.</b> 예전엔 판정과 동시에 lastRole을 갱신해, 웹훅이 잠깐 죽은 순간의 failover
     * 경보가 재감지조차 안 되고 영구 소실됐다(역할은 failover 후 머무르는 one-shot 신호라 재트리거도 없다).
     * split-brain은 기준선이 없어 안전했고 role-change만 이 함정이 있었다. detect()가 단일 흐름이라 평범한
     * 맵으로 충분하다(evict는 이 맵을 건드리지 않는다 — 패스마다 확정/폐기된다).
     */
    private final Map<Long, HaRole> pendingRole = new HashMap<>();

    /**
     * 직전 폴에서 "쓰기 가능 노드 ≥2"로 관측된 클러스터 — split-brain 히스테리시스용. 계획 스위치오버
     * 순간엔 옛/새 프라이머리가 잠깐 둘 다 쓰기 가능일 수 있어, 한 폴만 보고 알리면 오탐이 된다.
     * 2회 연속 관측될 때만 알린다(진짜 split-brain은 지속되므로 한 폴 늦게 잡힐 뿐이다).
     */
    private final Set<String> splitBrainSeen = new HashSet<>();

    HaObserver(WebhookNotifier notifier, CooldownGate cooldown) {
        this.notifier = notifier;
        this.cooldown = cooldown;
    }

    /**
     * 역할을 HA 관점의 굵은 분류로 환원한다 — 핵심은 <b>쓰기 가능(WRITABLE) vs 대기(STANDBY)</b>다.
     *
     * <p>왜 PRIMARY가 아니라 WRITABLE인가(라이브 검증에서 드러난 것): PG는 <b>연결된 복제본이 없는
     * primary를 STANDALONE으로 보고</b>한다. 스탠바이를 승격하면 그 순간 다운스트림이 없어 role이
     * STANDALONE이 되고, 원래 primary도 복제본을 잃으면 STANDALONE이 된다. 그래서 "PRIMARY 개수"로
     * split-brain을 세면 <b>실제 split-brain(양쪽 다 쓰기 가능)을 놓친다</b>. 승격/강등과 split-brain의
     * 본질은 "역할명이 PRIMARY인가"가 아니라 "쓰기를 받는가(=in-recovery가 아닌가)"다.
     * PRIMARY·STANDALONE은 WRITABLE, REPLICA/SECONDARY/STANDBY는 STANDBY, 그 외는 UNKNOWN.
     */
    enum HaRole { WRITABLE, STANDBY, UNKNOWN }

    static HaRole classify(String role) {
        if (role == null) {
            return HaRole.UNKNOWN;
        }
        String r = role.toUpperCase();
        if (r.contains("STANDBY") || r.contains("REPLICA") || r.contains("SECONDARY")) {
            return HaRole.STANDBY;
        }
        if (r.contains("PRIMARY") || r.contains("STANDALONE") || r.contains("MASTER") || r.contains("SOURCE")) {
            return HaRole.WRITABLE;
        }
        return HaRole.UNKNOWN;
    }

    /**
     * 역할 변경(failover 신호) — 직전 폴과 지금이 STANDBY↔WRITABLE로 뒤집히면 알린다(승격/강등).
     * 첫 관측(기준선 없음), UNKNOWN(역할 미상), 그리고 WRITABLE↔WRITABLE(예: primary가 복제본을
     * 잃어 STANDALONE이 됨 — 쓰기 상태는 그대로라 failover가 아니다)은 조용히 넘어간다.
     */
    List<String> roleChange(DatabaseInstance instance, ReplicationState state, LocalDateTime now) {
        if (state == null || instance.getId() == null) {
            return List.of();
        }
        HaRole cur = classify(state.role());
        if (cur == HaRole.UNKNOWN) {
            // 역할 미상 — 기준선을 건드리지 않는다(조회 실패를 역할 변경으로 오인하지 않는다).
            return List.of();
        }
        Long id = instance.getId();
        HaRole prev = lastRole.get(id);   // 읽기만 — 기준선 전진은 아래에서 조건부로
        if (prev == null) {
            lastRole.put(id, cur);        // 첫 관측: 기준선만 잡는다(알림 없음 → 전송 무관)
            return List.of();
        }
        if (prev == cur) {
            return List.of();             // 쓰기/대기 상태 변화 없음
        }
        String direction = cur == HaRole.WRITABLE ? "승격(STANDBY -> 쓰기 가능)" : "강등(쓰기 가능 -> STANDBY)";
        if (!cooldown.pass(id + ":role-change", now)) {
            return List.of();
        }
        // 기준선 전진은 전송 성공(commitPendingRole)으로 미룬다 — 웹훅 실패 시 failover 경보 소실 방지.
        pendingRole.put(id, cur);
        return List.of("복제 역할 변경 감지(failover 신호): %s (role=%s) — 계획된 스위치오버가 아니면 확인이 필요합니다"
                .formatted(direction, state.role()));
    }

    /**
     * 이번 폴에서 관측된 <b>쓰기 가능(WRITABLE)</b> 노드를 cluster 라벨로 모은다(split-brain 상관용).
     * PRIMARY만 세지 않는 이유는 classify 주석 참고 — 복제본을 잃은 primary는 STANDALONE으로 보고되지만
     * 여전히 쓰기를 받으므로, 정상 클러스터라면 쓰기 가능 노드는 정확히 하나여야 한다. 둘 이상이면 split-brain.
     * 라벨이 없으면 형제 노드를 알 수 없어 상관 불가라 건너뛴다.
     */
    void collectWritable(DatabaseInstance instance, ReplicationState state,
                         Map<String, List<Long>> writablesByCluster) {
        if (state == null || instance.getId() == null || classify(state.role()) != HaRole.WRITABLE) {
            return;
        }
        String cluster = instance.getClusterLabel();
        if (cluster == null || cluster.isBlank()) {
            return;
        }
        writablesByCluster.computeIfAbsent(cluster, k -> new ArrayList<>()).add(instance.getId());
    }

    /**
     * split-brain 판정 — 한 클러스터(cluster 라벨)에 쓰기 가능 노드가 둘 이상이면 알린다. 복제 토폴로지에서
     * 가장 위험한 상태다(양쪽이 각자 쓰기를 받아 데이터가 갈라진다). DBTower는 이걸 막지 못하고(펜싱은
     * 클러스터 매니저 몫) 감지·기록만 한다. 쿨다운은 클러스터 단위이고, 전송이 성공해야 확정한다.
     *
     * <p><b>전제: 단일 프라이머리 토폴로지</b>(PG 스트리밍·MySQL 클래식·MSSQL AlwaysOn·Oracle DG·Mongo
     * 복제셋 — 쓰기 가능 노드가 정확히 하나). <b>의도적 멀티라이터</b>(MySQL Group Replication 멀티
     * 프라이머리·Galera/XtraDB Cluster·active-active)는 같은 cluster 라벨로 묶으면 오탐한다 — 그런
     * 클러스터에는 라벨을 붙이지 않거나(상관 대상에서 제외) 별도 정책이 필요하다. GR 단일 프라이머리는
     * MySqlOperator가 replication_group_members로 SECONDARY를 정직하게 줘서 오탐하지 않는다(123.23).
     */
    void splitBrain(Map<String, List<Long>> writablesByCluster,
                    Map<Long, DatabaseInstance> byId, LocalDateTime now) {
        Set<String> nowSeen = new HashSet<>();
        for (var entry : writablesByCluster.entrySet()) {
            List<Long> writableIds = entry.getValue();
            if (writableIds.size() < 2) {
                continue;
            }
            String cluster = entry.getKey();
            nowSeen.add(cluster);
            // 히스테리시스 — 첫 관측이면 한 폴 더 기다린다(계획 스위치오버의 순간적 이중 쓰기 필터).
            if (!splitBrainSeen.contains(cluster)) {
                continue;
            }
            String cdKey = "split-brain:" + cluster;
            if (cooldown.isCoolingDown(cdKey, now)) {
                continue;
            }
            List<String> names = writableIds.stream().map(id -> byId.get(id).getName()).toList();
            DatabaseInstance rep = byId.get(writableIds.get(0));
            List<String> findings = List.of(
                    "split-brain 의심 — 클러스터 '%s'에 쓰기 가능 노드가 %d개입니다: %s. 단일 프라이머리 클러스터라면 쓰기 노드는 하나여야 합니다 — 펜싱·정족수를 확인하세요"
                            .formatted(cluster, writableIds.size(), String.join(", ", names)));
            if (notifySplitBrain(rep, findings)) {
                cooldown.mark(cdKey, now);   // 전송 성공 후에만 쿨다운 확정
            } else {
                log.warn("split-brain 경보 전송 실패 cluster={} — 쿨다운 미확정, 다음 주기 재시도", cluster);
            }
        }
        // 다음 폴의 히스테리시스 기준 — 이번에 ≥2로 본 클러스터로 교체(사라진 건 자연히 초기화된다).
        splitBrainSeen.clear();
        splitBrainSeen.addAll(nowSeen);
    }

    /** 전송 성공 — 이제 역할 기준선을 전진시킨다(그 전엔 실패 시 재감지 가능하게 미뤄뒀다). */
    void commitPendingRole() {
        pendingRole.forEach(lastRole::put);
        pendingRole.clear();
    }

    /** 전송 실패·무발사 — 기준선 전진을 취소한다(다음 폴에서 재감지). */
    void clearPendingRole() {
        pendingRole.clear();
    }

    /** 인스턴스 삭제 — 그 인스턴스의 역할 기준선을 비운다. */
    void evict(long instanceId) {
        lastRole.remove(instanceId);
    }

    private boolean notifySplitBrain(DatabaseInstance rep, List<String> findings) {
        StringBuilder message = new StringBuilder();
        message.append("[DBTower HA 경보] split-brain 의심\n");
        findings.forEach(f -> message.append("- ").append(f).append("\n"));
        log.warn("HA 경보 split-brain rep={} findings={}", rep.getName(), findings.size());
        return notifier.sendEmbed(message.toString(), rep.getId(), AlertEmbeds.forDetection(
                "HA 경보 — split-brain", AlertEmbeds.RED, rep,
                null, null, findings, null, null));
    }
}
