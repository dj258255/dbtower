package io.dbtower.finops;

import io.dbtower.advisor.AdvisorFinding;
import io.dbtower.advisor.DuplicateIndexAdvisor;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 중복·잉여 인덱스 후보 (D6) — <b>D2의 DuplicateIndexAdvisor를 그대로 재사용</b>한다. 판정 로직을 복제하지
 * 않고 D2 빈을 주입해 evaluate(describeSchema())를 호출하고, 결과 AdvisorFinding을 FinOps 낭비 후보로 옮긴다.
 *
 * 왜 재사용인가: 같은 "겹치는 인덱스" 판정을 두 곳에서 따로 구현하면 규칙이 갈라진다. D6의 관점(낭비 신호)과
 * D2의 관점(Advisor 점검)은 표현만 다르고 판정은 동일해야 하므로, 단일 출처(DuplicateIndexAdvisor)를 공유한다.
 * 미사용 인덱스(UnusedIndexAnalyzer, 사용 통계)와도 상호보완이다 — 여기는 구조상 겹침, 저기는 실측 미사용.
 */
@Component
public class RedundantIndexAnalyzer implements FinOpsAnalyzer {

    private final DuplicateIndexAdvisor duplicateIndexAdvisor;

    public RedundantIndexAnalyzer(DuplicateIndexAdvisor duplicateIndexAdvisor) {
        this.duplicateIndexAdvisor = duplicateIndexAdvisor;
    }

    @Override
    public String id() {
        return "redundant-index";
    }

    @Override
    public String title() {
        return "중복·잉여 인덱스 후보 (D2 구조 판정 재사용)";
    }

    @Override
    public boolean supports(DbmsType type) {
        // describeSchema 기반이라 5기종 모두 지원(D2 DuplicateIndexAdvisor.supports와 동일)
        return duplicateIndexAdvisor.supports(type);
    }

    @Override
    public List<WasteCandidate> analyze(DatabaseInstance instance, DbmsOperator operator) {
        // D2 판정을 그대로 호출 — 규칙의 단일 출처. AdvisorFinding → WasteCandidate로 표현만 옮긴다.
        List<AdvisorFinding> findings = duplicateIndexAdvisor.evaluate(operator.describeSchema());
        return findings.stream()
                .map(f -> new WasteCandidate(WasteKind.REDUNDANT_INDEX, f.severity(),
                        f.title(), f.detail(), f.recommendation()))
                .toList();
    }
}
