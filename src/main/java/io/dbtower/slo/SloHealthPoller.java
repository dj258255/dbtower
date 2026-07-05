package io.dbtower.slo;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.HealthStatus;
import io.dbtower.registry.RegistryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 가용성 SLI 원자료 수집 (Phase D4) — 등록된 모든 인스턴스의 up/down을 주기적으로 HealthSample에 남긴다.
 *
 * 에러 버짓은 "지난 기간 중 얼마나 up이었나"의 시계열이 있어야 계산된다. 요청 시점에만 계산하고 버리던
 * 헬스체크(RegistryService.health)를 여기서 주기 관측으로 바꿔 이력을 쌓는다.
 * 수집 주기(dbtower.slo.availability.poll-ms)가 곧 버짓 산식의 샘플 간격이다 — 다운 샘플 1개 ≈ 이 시간의 다운타임.
 */
@Component
public class SloHealthPoller {

    private static final Logger log = LoggerFactory.getLogger(SloHealthPoller.class);

    private final DatabaseInstanceRepository instanceRepository;
    private final RegistryService registryService;
    private final HealthSampleRepository sampleRepository;

    public SloHealthPoller(DatabaseInstanceRepository instanceRepository,
                           RegistryService registryService,
                           HealthSampleRepository sampleRepository) {
        this.instanceRepository = instanceRepository;
        this.registryService = registryService;
        this.sampleRepository = sampleRepository;
    }

    // HA 분산 락(Phase A5): 여러 노드가 동시에 같은 인스턴스를 관측해 이중 계상하지 않게 한 시점 한 노드만.
    // lockAtLeastFor는 주기의 대부분을 붙잡아 노드 간 시계 드리프트로 인한 한 주기 중복 관측을 막는다.
    @Scheduled(fixedDelayString = "${dbtower.slo.availability.poll-ms:60000}")
    @SchedulerLock(name = "slo-health-sample", lockAtLeastFor = "PT50S", lockAtMostFor = "PT2M")
    public void sample() {
        LocalDateTime now = LocalDateTime.now();
        List<HealthSample> rows = new ArrayList<>();
        for (DatabaseInstance instance : instanceRepository.findAll()) {
            // health()는 대상이 죽어 있어도 예외를 삼키고 down으로 돌려준다 — down도 가용성 이력의 일부라 그대로 기록한다.
            HealthStatus health = registryService.health(instance.getId());
            rows.add(new HealthSample(instance.getId(), now, health.up(), health.pingMillis()));
        }
        if (!rows.isEmpty()) {
            sampleRepository.saveAll(rows);
            log.debug("헬스 샘플 수집 완료 instances={} up={}", rows.size(),
                    rows.stream().filter(HealthSample::isUp).count());
        }
    }
}
