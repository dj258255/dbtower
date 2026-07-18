package io.dbtower.backup.internal.job;

import java.lang.ProcessBuilder.Redirect;
import io.dbtower.backup.internal.persistence.BackupPolicyRepository;
import io.dbtower.operator.BackupCommands;
import io.dbtower.operator.model.BackupPolicy.BackupType;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.InstanceDeletedEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pg_receivewal 스트리밍 상주 관리 (Phase 2 잔여 — WAL 무결 연속의 정석).
 *
 * 풀 방식(pg_switch_wal 후 완결 세그먼트 수집)은 수집 주기 사이에 세그먼트가 재활용되면 체인에
 * 구멍이 날 수 있다. pg_receivewal은 복제 프로토콜로 WAL을 <b>실시간 스트리밍</b> 수신해 그 창을
 * 없앤다 — PostgreSQL 문서가 아카이빙의 정석으로 안내하는 도구다. 여기에 복제 슬롯(--slot)을
 * 쓰면 수신자가 죽어 있는 동안의 WAL도 서버가 보존해 재시작 후 이어받는다(유실 0).
 *
 * 관리 모델: 상주 프로세스는 "실행"이 아니라 "보살핌"이 일이다 — 30초마다 (1) 대상 집합 계산
 * (PG 기종 + LOG 정책 enabled + 명령 설정), (2) 죽은 스트림 재기동(슬롯 덕에 공백 무손실),
 * (3) 대상에서 빠진 스트림 종료. 슬롯은 시작 전에 --create-slot --if-not-exists로 멱등 생성한다.
 *
 * 정직한 한계 두 가지를 명시한다:
 * - 슬롯은 배타적이라 다중 노드가 같이 스트리밍하면 두 번째는 실패를 반복한다 — 이 기능은
 *   노드 1곳에만 설정하는 것을 전제로 한다(기능 게이트: 명령 미설정 노드는 아무것도 안 함).
 * - 미소비 슬롯은 WAL을 무한 보존해 디스크를 채운다 — 스트림을 끌 때는 슬롯도 지워야 하며,
 *   이 위험 자체는 OpsAlertDetector의 복제 슬롯 감시(wal_status·보존량)가 이미 잡는다.
 * 산출물 장부(backup_run) 연동은 풀 방식 LOG 백업의 몫으로 남긴다 — 스트리밍은 연속성의
 * 안전망이고, 이력·신선도·PITR 창 계산은 기존 경로가 담당한다(중복 계상 방지).
 */
@Component
public class WalStreamManager {

    private static final Logger log = LoggerFactory.getLogger(WalStreamManager.class);

    private final DatabaseInstanceRepository instanceRepository;
    private final BackupPolicyRepository policyRepository;
    private final String receivewalCommand;

    private final Map<Long, Process> streams = new ConcurrentHashMap<>();
    /** 재시작 횟수(관측용) — e2e가 "죽인 프로세스가 되살아났다"를 이 카운터·로그로 확인한다. */
    private final Map<Long, Integer> restarts = new ConcurrentHashMap<>();

    public WalStreamManager(DatabaseInstanceRepository instanceRepository,
                            BackupPolicyRepository policyRepository,
                            @Value("${dbtower.backup.pg-receivewal-command:}") String receivewalCommand) {
        this.instanceRepository = instanceRepository;
        this.policyRepository = policyRepository;
        this.receivewalCommand = receivewalCommand == null ? "" : receivewalCommand;
    }

    @Scheduled(fixedDelayString = "${dbtower.backup.wal-stream-check-ms:30000}")
    public void ensureStreams() {
        if (receivewalCommand.isBlank()) {
            return; // 기능 게이트 — 미설정 노드는 스트리밍 소유자가 아니다
        }
        Set<Long> desired = desiredInstanceIds();
        // 대상에서 빠진 스트림 종료(정책 off·격리·삭제)
        for (Long id : new HashSet<>(streams.keySet())) {
            if (!desired.contains(id)) {
                stop(id, "대상 제외(정책 변경·격리)");
            }
        }
        // 죽었거나 아직 없는 스트림 기동
        for (Long id : desired) {
            Process p = streams.get(id);
            if (p != null && p.isAlive()) {
                continue;
            }
            instanceRepository.findById(id).ifPresent(instance -> {
                if (p != null) {
                    int n = restarts.merge(id, 1, Integer::sum);
                    log.warn("WAL 스트림 사망 감지 — instance={} 재시작 {}회차 (슬롯이 공백 구간을 보존한다)",
                            instance.getName(), n);
                }
                start(instance);
            });
        }
    }

    /** 스트리밍 대상 = PG 기종 + LOG 정책 enabled + 수집 격리 아님. */
    Set<Long> desiredInstanceIds() {
        Set<Long> desired = new HashSet<>();
        for (var policy : policyRepository.findByEnabledTrue()) {
            if (policy.getType() != BackupType.LOG) {
                continue;
            }
            instanceRepository.findById(policy.getInstanceId()).ifPresent(i -> {
                if (i.getType() == DbmsType.POSTGRESQL && i.isCollectionEnabled()) {
                    desired.add(i.getId());
                }
            });
        }
        return desired;
    }

    private void start(DatabaseInstance instance) {
        try {
            List<String> base = BackupCommands.render(receivewalCommand, instance);
            // 1) 슬롯 멱등 생성(즉시 종료) — 슬롯이 있어야 수신자 공백 구간의 WAL이 보존된다
            List<String> createSlot = new ArrayList<>(base);
            createSlot.add("--create-slot");
            createSlot.add("--if-not-exists");
            Process create = spawn(instance, createSlot);
            create.waitFor();
            // 2) 스트리밍 본체(상주)
            Process stream = spawn(instance, base);
            streams.put(instance.getId(), stream);
            log.info("WAL 스트림 시작 — instance={} pid={}", instance.getName(), stream.pid());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("WAL 스트림 시작 실패 — instance={} cause={} (다음 점검에서 재시도)",
                    instance.getName(), e.getMessage());
        }
    }

    private Process spawn(DatabaseInstance instance, List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        // 비밀번호는 argv 금지 — PGPASSWORD 환경변수로(도커 템플릿은 -e PGPASSWORD로 전달)
        pb.environment().put("PGPASSWORD", instance.getPassword());
        pb.redirectErrorStream(true);
        pb.redirectOutput(Redirect.DISCARD);
        Process p = pb.start();
        // stdin 즉시 EOF — 함정(실측): docker exec -i류 래퍼는 원격 프로세스가 죽어도 stdin이 열려
        // 있으면 CLI가 살아남아, isAlive() 생존 감시가 죽은 스트림을 산 것으로 오인한다(재시작 불발).
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // 이미 닫혔으면 그만 — 목적은 EOF 전달뿐
        }
        return p;
    }

    private void stop(Long instanceId, String reason) {
        Process p = streams.remove(instanceId);
        restarts.remove(instanceId);
        if (p != null && p.isAlive()) {
            p.destroy();
            log.info("WAL 스트림 종료 — instanceId={} ({})", instanceId, reason);
        }
    }

    @EventListener
    public void onInstanceDeleted(InstanceDeletedEvent event) {
        stop(event.instanceId(), "인스턴스 삭제");
    }

    @PreDestroy
    void shutdown() {
        streams.keySet().forEach(id -> stop(id, "앱 종료"));
    }
}
