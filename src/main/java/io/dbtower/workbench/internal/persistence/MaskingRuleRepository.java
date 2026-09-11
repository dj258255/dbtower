package io.dbtower.workbench.internal.persistence;

import io.dbtower.workbench.internal.domain.MaskingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MaskingRuleRepository extends JpaRepository<MaskingRule, Long> {

    /** 인스턴스 규칙을 공통 규칙보다 먼저 — 첫 매치가 이기므로 순서가 곧 우선순위다. */
    @Query("select r from MaskingRule r where r.instanceId = :instanceId or r.instanceId is null "
            + "order by case when r.instanceId is null then 1 else 0 end, r.id")
    List<MaskingRule> findApplicable(@Param("instanceId") Long instanceId);
}
