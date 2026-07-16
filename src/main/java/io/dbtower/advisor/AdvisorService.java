package io.dbtower.advisor;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Advisor 오케스트레이션 (Phase D2) — 등록된 Advisor 전부를 한 인스턴스에 적용해 리포트를 만든다.
 *
 * 기종 적용은 각 Advisor의 supports가 결정하고, 미지원은 UNSUPPORTED로 정직하게 남긴다(위장 금지).
 * Advisor 하나의 실패가 나머지 점검을 막지 않도록 개별 격리한다(ERROR로 기록). 대상 DB는 읽기만 한다.
 */
@Service
public class AdvisorService {

    private static final Logger log = LoggerFactory.getLogger(AdvisorService.class);

    private final List<Advisor> advisors;
    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;

    public AdvisorService(List<Advisor> advisors, RegistryService registryService,
                          DbmsOperatorFactory operatorFactory) {
        this.advisors = advisors;
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
    }

    /** 인스턴스 id로 온디맨드 점검 — REST가 부른다. */
    public InstanceAdvisorReport inspect(Long instanceId) {
        return inspect(registryService.findById(instanceId));
    }

    /** 인스턴스 하나에 전 Advisor를 적용한다. operator는 supports=true인 Advisor가 있을 때만 만든다. */
    public InstanceAdvisorReport inspect(DatabaseInstance instance) {
        return inspect(instance, Map.of());
    }

    /**
     * 스윕용 dedup 지원 (Phase 4 — 서버 공유 인지): sharedBy에 담긴 Advisor(id → 이미 같은 호스트를
     * 점검한 인스턴스 이름)는 실행하지 않고 SHARED로 표기한다. 온디맨드 단건 점검(REST·헬스 스코어)은
     * 빈 맵으로 들어와 전부 실행된다 — dedup은 반복 탐침·중복 지적에만, 위험 귀속에는 하지 않는다.
     */
    public InstanceAdvisorReport inspect(DatabaseInstance instance, Map<String, String> sharedBy) {
        List<AdvisorCheck> checks = new ArrayList<>();
        DbmsOperator operator = null;
        for (Advisor advisor : advisors) {
            String coveredBy = sharedBy.get(advisor.id());
            if (coveredBy != null) {
                checks.add(AdvisorCheck.shared(advisor, coveredBy));
                continue;
            }
            if (!advisor.supports(instance.getType())) {
                checks.add(AdvisorCheck.unsupported(advisor));
                continue;
            }
            try {
                if (operator == null) {
                    operator = operatorFactory.create(instance);
                }
                List<AdvisorFinding> findings = advisor.inspect(instance, operator);
                checks.add(findings.isEmpty()
                        ? AdvisorCheck.ok(advisor)
                        : AdvisorCheck.violations(advisor, findings));
            } catch (Exception e) {
                // 점검 하나의 실패(접속 불가·권한 부족 등)가 리포트 전체를 죽이지 않게 격리
                log.warn("Advisor 실행 실패 advisor={} instance={} cause={}",
                        advisor.id(), instance.getName(), e.getMessage());
                checks.add(AdvisorCheck.error(advisor, e.getMessage()));
            }
        }
        return InstanceAdvisorReport.of(instance.getId(), instance.getName(), instance.getType(),
                LocalDateTime.now(), checks);
    }
}
