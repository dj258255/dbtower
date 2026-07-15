package io.dbtower.advisor.internal.job;

import io.dbtower.advisor.AdvisorService;
import io.dbtower.advisor.InstanceAdvisorReport;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 일일 Advisor 스윕 (Phase D2) — PMM Advisors가 24h 배경 점검을 도는 것과 같은 구도.
 *
 * 전 인스턴스 x 전 Advisor를 주기 실행(기본 24h)해 최신 리포트를 메모리에 보관하고 요약을 로그로 남긴다.
 * 온디맨드 REST(AdvisorController)는 항상 새로 계산하고, 이 스윕은 "마지막으로 본 상태"를 캐시한다.
 *
 * HA 분산 락(Phase A5): RegressionDetector·OpsAlertDetector와 같은 이유로 한 시점에 한 노드만 스윕한다.
 * 점검은 읽기 전용이라 중복 실행도 안전하지만, 불필요한 대상 DB 부하와 로그 중복을 막는다.
 */
@Component
public class AdvisorSweepJob {

    private static final Logger log = LoggerFactory.getLogger(AdvisorSweepJob.class);

    private final DatabaseInstanceRepository instanceRepository;
    private final AdvisorService advisorService;

    /** 인스턴스별 마지막 스윕 리포트 — 캐시(결과 저장). */
    private final Map<Long, InstanceAdvisorReport> latest = new ConcurrentHashMap<>();

    public AdvisorSweepJob(DatabaseInstanceRepository instanceRepository, AdvisorService advisorService) {
        this.instanceRepository = instanceRepository;
        this.advisorService = advisorService;
    }

    // lockAtMostFor를 넉넉히(PT30M) 둔다 — 인스턴스가 많고 각 operator 조회가 느릴 수 있어
    // 정상 스윕 중 다른 노드가 끼어들지 않도록 실제 소요보다 큰 크래시 상한을 잡는다.
    @Scheduled(fixedDelayString = "${dbtower.advisor.sweep-ms:86400000}", initialDelayString = "${dbtower.advisor.initial-delay-ms:60000}")
    @SchedulerLock(name = "advisor-sweep", lockAtLeastFor = "PT10S", lockAtMostFor = "PT30M")
    public void sweep() {
        int instances = 0, critical = 0, warning = 0;
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            try {
                InstanceAdvisorReport report = advisorService.inspect(instance);
                latest.put(instance.getId(), report);
                instances++;
                critical += report.critical();
                warning += report.warning();
                if (report.critical() > 0 || report.warning() > 0) {
                    log.info("Advisor 스윕 instance={} critical={} warning={} info={}",
                            instance.getName(), report.critical(), report.warning(), report.info());
                }
            } catch (Exception e) {
                // 인스턴스 하나의 실패가 스윕 전체를 멈추지 않게 격리(개별 Advisor 격리는 서비스에서 이미 처리)
                log.warn("Advisor 스윕 실패 instance={} cause={}", instance.getName(), e.getMessage());
            }
        }
        log.info("Advisor 스윕 완료 instances={} critical={} warning={}", instances, critical, warning);
    }

    /** 스윕이 마지막으로 본 리포트(있으면) — 캐시 조회용. */
    public Optional<InstanceAdvisorReport> lastReport(Long instanceId) {
        return Optional.ofNullable(latest.get(instanceId));
    }
}
