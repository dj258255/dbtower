package io.dbtower.advisor;

import io.dbtower.registry.DbmsType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 한 인스턴스의 전체 Advisor 점검 리포트 — 웹 콘솔 "Advisors" 카드와 REST 응답의 한 단위.
 *
 * critical/warning/info는 VIOLATIONS 지적을 심각도별로 센 값이다(카드 상단 뱃지·나쁜 순 정렬용).
 *
 * @param instanceId   대상 인스턴스 id
 * @param instanceName 인스턴스 이름
 * @param type         기종
 * @param checkedAt    점검 시각
 * @param checks       Advisor별 결과
 * @param critical     CRITICAL 지적 수
 * @param warning      WARNING 지적 수
 * @param info         INFO 지적 수
 */
public record InstanceAdvisorReport(Long instanceId, String instanceName, DbmsType type,
                                    LocalDateTime checkedAt, List<AdvisorCheck> checks,
                                    int critical, int warning, int info) {

    /** 심각도별 집계는 checks에서 파생 — 호출부가 세지 않도록 여기서 계산해 담는다. */
    public static InstanceAdvisorReport of(Long instanceId, String instanceName, DbmsType type,
                                           LocalDateTime checkedAt, List<AdvisorCheck> checks) {
        int critical = 0, warning = 0, info = 0;
        for (AdvisorCheck check : checks) {
            for (AdvisorFinding f : check.findings()) {
                switch (f.severity()) {
                    case CRITICAL -> critical++;
                    case WARNING -> warning++;
                    case INFO -> info++;
                }
            }
        }
        return new InstanceAdvisorReport(instanceId, instanceName, type, checkedAt,
                List.copyOf(checks), critical, warning, info);
    }
}
