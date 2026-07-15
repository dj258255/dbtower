package io.dbtower.insight.internal.job;

import io.dbtower.insight.QuerySnapshotRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 스냅샷 보존 정책 — 보존 기간이 지난 QuerySnapshot을 주기적으로 정리한다.
 *
 * 수집기(SnapshotScheduler)는 60초마다 인스턴스당 최대 100행을 쌓는데
 * 지금까지 삭제 로직이 없어 무한 적재였다. 인스턴스 5대면 하루 72만 행 —
 * 진단 플랫폼의 메타 DB가 관리 대상보다 먼저 포화되는 구조적 한계.
 *
 * 기본 보존 7일은 AWS RDS Performance Insights의 선례를 따른다 —
 * PI도 기본 보존이 7일이고 그 이상은 명시적 선택이다.
 * 장기 보존이 필요하면 retention-days를 0 이하로 두어 정리를 끈다.
 */
@Component
public class SnapshotRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRetentionJob.class);

    private final QuerySnapshotRepository snapshotRepository;
    private final int retentionDays;

    public SnapshotRetentionJob(QuerySnapshotRepository snapshotRepository,
                                @Value("${dbtower.snapshot.retention-days:7}") int retentionDays) {
        this.snapshotRepository = snapshotRepository;
        this.retentionDays = retentionDays;
    }

    // 벌크 DELETE(@Modifying)는 트랜잭션 안에서만 실행 가능 — 스케줄 스레드에는
    // 열린 트랜잭션이 없으므로 여기서 경계를 연다.
    // HA 분산 락(Phase A5): 여러 노드가 동시에 같은 벌크 DELETE를 돌리지 않게 한 노드만 정리한다.
    // 삭제 자체는 멱등하지만(이미 지운 행을 또 지우면 0건), 동시 실행은 불필요한 DB 부하와 로그 중복이라 막는다.
    // lockAtLeastFor=PT30S — 1시간 주기라 드리프트로 인한 같은 주기 중복 가능성은 낮지만, 두 노드가
    //   거의 동시에 틱했을 때의 중복만 잠깐 눌러주면 충분하다(멱등하므로 길게 붙잡을 이유는 없다).
    // lockAtMostFor=PT10M — 대량 삭제가 오래 걸려도 다른 노드가 끼어들지 않도록 넉넉한 크래시 상한.
    // 주의: @Transactional과 함께 쓸 때 락 획득은 프록시 바깥(스케줄러 진입)에서 일어나므로,
    //   ShedLock이 스킵하면 트랜잭션 자체가 열리지 않는다 — 열린 빈 트랜잭션이 남는 문제는 없다.
    @Scheduled(fixedDelayString = "${dbtower.snapshot.retention-sweep-ms:3600000}")
    @SchedulerLock(name = "snapshot-retention-sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    @Transactional
    public void sweep() {
        if (retentionDays <= 0) {
            // 보존 무제한 — 운영자가 명시적으로 끈 상태이므로 조용히 지나간다
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = snapshotRepository.deleteByCapturedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("스냅샷 보존 정리 완료 deleted={} cutoff={} retentionDays={}",
                    deleted, cutoff, retentionDays);
        } else {
            // 정상 상태(삭제할 것 없음)가 매시간 INFO를 채우면 노이즈 — debug로 내린다
            log.debug("스냅샷 보존 정리 — 삭제 대상 없음 cutoff={}", cutoff);
        }
    }
}
