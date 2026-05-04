package io.dbtower.backup;

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

    @Scheduled(fixedDelayString = "${dbtower.backup.poll-ms:30000}")
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
