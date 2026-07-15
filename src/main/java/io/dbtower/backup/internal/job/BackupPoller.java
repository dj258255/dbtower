package io.dbtower.backup.internal.job;

import io.dbtower.backup.internal.BackupService;
import io.dbtower.backup.internal.domain.BackupPolicyEntity;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 30초마다 만기된 백업 정책을 찾아 실행한다.
 * 동적 cron 스케줄러 대신 폴러를 쓴 이유: 정책이 런타임에 추가/수정되는데,
 * 폴러는 재시작·재등록 없이 그 변경을 자연스럽게 반영하고 구현이 단순하다.
 */
@Component
public class BackupPoller {

    private static final Logger log = LoggerFactory.getLogger(BackupPoller.class);

    private final BackupService backupService;

    public BackupPoller(BackupService backupService) {
        this.backupService = backupService;
    }

    // HA 분산 락(Phase A5): 여러 노드가 동시에 같은 정책으로 pg_dump/mysqldump를 돌리면
    // 백업 중복·저장소 낭비·markRun 경합이 생긴다 — 한 시점에 한 노드만 실행하게 막는다.
    // (스펙에 명시된 세 폴러 외 추가분: 백업도 같은 단일 프로세스 전제라 HA에서 같은 결함을 가진다.)
    // lockAtLeastFor=PT25S — 30초 주기 대부분을 붙잡아 노드 드리프트로 인한 중복 백업을 막는다.
    // lockAtMostFor=PT10M — 덤프는 DB 크기에 따라 오래 걸리므로, 실행 중 다른 노드가 끼어들지 않도록
    //   실제 백업 시간보다 넉넉한 크래시 상한을 둔다.
    @Scheduled(fixedDelayString = "${dbtower.backup.poll-ms:30000}")
    @SchedulerLock(name = "backup-poller", lockAtLeastFor = "PT25S", lockAtMostFor = "PT10M")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        for (BackupPolicyEntity policy : backupService.duePolicies(now)) {
            log.info("백업 정책 실행 instanceId={} type={} interval={}m",
                    policy.getInstanceId(), policy.getType(), policy.getIntervalMinutes());
            backupService.runNow(policy.getInstanceId(), policy.getType());
            backupService.markRun(policy, now);
        }
    }
}
