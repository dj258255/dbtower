package io.dbtower.alert;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행계획 스냅샷 보존 정리 (WS-B B-2) — plan_snapshot은 계획 변경(plan flip) 때만 append되지만
 * 삭제 로직이 없어 무한 성장이었다(query_snapshot·health_sample은 보존 잡이 있는데 이것만 없었다).
 *
 * <p>보존 방식은 시간이 아니라 <b>(instance, query)별 최신 N개</b>다 — 계획 이력은 "이 쿼리가 그동안
 * 어떤 계획들을 오갔나"의 세대 기록이라, 며칠이 지났나보다 "몇 세대 남길까"가 자연스럽다. 최신 N개만 남기면
 * 가장 최근 기준선(변경 판정에 필요한 직전 스냅샷)은 항상 보존된다.
 *
 * <p>기존 두 보존 잡(SnapshotRetentionJob·HealthSampleRetentionJob)의 패턴을 그대로 따른다:
 * 벌크 DELETE라 트랜잭션 경계를 열고, HA에서 여러 노드가 같은 삭제를 중복 실행하지 않게 ShedLock으로
 * 한 노드만 돌린다(삭제는 멱등하지만 불필요한 DB 부하·로그 중복을 막는다). keep이 0 이하면 보존 무제한(정리 안 함).
 */
@Component
public class PlanSnapshotRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(PlanSnapshotRetentionJob.class);

    private final PlanSnapshotRepository repository;
    private final int keepPerQuery;

    public PlanSnapshotRetentionJob(PlanSnapshotRepository repository,
                                    @Value("${dbtower.plan-snapshot.retention-per-query:20}") int keepPerQuery) {
        this.repository = repository;
        this.keepPerQuery = keepPerQuery;
    }

    @Scheduled(fixedDelayString = "${dbtower.plan-snapshot.retention-sweep-ms:3600000}")
    @SchedulerLock(name = "plan-snapshot-retention-sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    @Transactional
    public void sweep() {
        if (keepPerQuery <= 0) {
            // 보존 무제한 — 운영자가 명시적으로 끈 상태이므로 조용히 지나간다
            return;
        }
        int deleted = repository.deleteExceedingPerQuery(keepPerQuery);
        if (deleted > 0) {
            log.info("플랜 스냅샷 보존 정리 완료 deleted={} keepPerQuery={}", deleted, keepPerQuery);
        } else {
            // 정상 상태(삭제할 것 없음)가 매시간 INFO를 채우면 노이즈 — debug로 내린다
            log.debug("플랜 스냅샷 보존 정리 — 삭제 대상 없음 keepPerQuery={}", keepPerQuery);
        }
    }
}
