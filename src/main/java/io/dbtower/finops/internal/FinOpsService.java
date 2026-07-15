package io.dbtower.finops.internal;

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

/**
 * FinOps 오케스트레이션 (D6) — 등록된 분석기 전부를 한 인스턴스에 적용해 낭비 후보 리포트를 만든다.
 * AdvisorService(D2)와 같은 구조: 기종 적용은 각 분석기의 supports가 정하고, 미지원은 UNSUPPORTED로
 * 정직하게 남긴다(위장 금지). 분석기 하나의 실패가 리포트 전체를 죽이지 않게 개별 격리한다. 대상 DB는 읽기만.
 *
 * 정체성: 여기서 나오는 건 전부 "신호·조언"이다 — 인덱스 자동 삭제도, rightsizing 실행도, 절감액(달러)
 * 산출도 하지 않는다(실제 과금 연동은 자격증명 필요라 범위 밖). "절감 가능 신호"까지만 낸다.
 */
@Service
public class FinOpsService {

    private static final Logger log = LoggerFactory.getLogger(FinOpsService.class);

    private final List<FinOpsAnalyzer> analyzers;
    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;

    public FinOpsService(List<FinOpsAnalyzer> analyzers, RegistryService registryService,
                         DbmsOperatorFactory operatorFactory) {
        this.analyzers = analyzers;
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
    }

    /** 인스턴스 id로 온디맨드 분석 — REST가 부른다. */
    public InstanceFinOpsReport analyze(Long instanceId) {
        return analyze(registryService.findById(instanceId));
    }

    /** 인스턴스 하나에 전 분석기를 적용한다. operator는 supports=true인 분석기가 있을 때만 만든다. */
    public InstanceFinOpsReport analyze(DatabaseInstance instance) {
        List<FinOpsCheck> checks = new ArrayList<>();
        DbmsOperator operator = null;
        for (FinOpsAnalyzer analyzer : analyzers) {
            if (!analyzer.supports(instance.getType())) {
                checks.add(FinOpsCheck.unsupported(analyzer, "이 기종에는 적용되지 않는 분석이다"));
                continue;
            }
            try {
                if (operator == null) {
                    operator = operatorFactory.create(instance);
                }
                List<WasteCandidate> candidates = analyzer.analyze(instance, operator);
                checks.add(candidates.isEmpty()
                        ? FinOpsCheck.ok(analyzer)
                        : FinOpsCheck.candidates(analyzer, candidates));
            } catch (Exception e) {
                // 분석 하나의 실패(접속 불가·권한 부족·통계 뷰 미가용 등)가 리포트 전체를 죽이지 않게 격리
                log.warn("FinOps 분석 실패 analyzer={} instance={} cause={}",
                        analyzer.id(), instance.getName(), e.getMessage());
                checks.add(FinOpsCheck.error(analyzer, e.getMessage()));
            }
        }
        return InstanceFinOpsReport.of(instance.getId(), instance.getName(), instance.getType(),
                LocalDateTime.now(), checks);
    }
}
