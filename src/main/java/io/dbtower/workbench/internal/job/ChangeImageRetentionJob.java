package io.dbtower.workbench.internal.job;

import io.dbtower.workbench.internal.persistence.ChangeExecutionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 변경 행 사본의 보존 기한 정리. 사본은 되돌리기를 위해 원래 값(개인정보 포함)을 그대로 담으므로, 암호화해 두더라도 무기한 쌓지
 * 않는다 — 기한이 지나면 사본을 지우고 그 실행의 되돌리기를 닫는다. 실행 기록(누가·언제·몇 행·해시)은 감사용으로 남긴다.
 * 다른 보존 잡과 같이 HA에서 한 노드만 돌도록 ShedLock을 건다.
 */
@Component
public class ChangeImageRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ChangeImageRetentionJob.class);

    static final String EXPIRED_NOTE = "보존 기한이 지나 행 사본을 지웠다. 이 실행은 더 이상 사본으로 되돌릴 수 없다";

    private final ChangeExecutionRepository repository;

    public ChangeImageRetentionJob(ChangeExecutionRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${dbtower.workbench.change.image-sweep-ms:3600000}")
    @SchedulerLock(name = "workbench-change-image-retention", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    @Transactional
    public void sweep() {
        int expired = repository.expireImages(LocalDateTime.now(), EXPIRED_NOTE);
        if (expired > 0) {
            log.info("변경 행 사본 보존 만료: {}건의 사본을 지우고 되돌리기를 닫았다", expired);
        }
    }
}
