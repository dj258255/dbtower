package io.dbtower.finops;

import io.dbtower.registry.DbmsType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 한 인스턴스의 전체 FinOps 리포트 (D6) — 웹 콘솔 "비용/효율" 카드와 REST 응답의 한 단위.
 *
 * candidateCount는 전 분석기의 낭비 후보 총수(카드 상단 뱃지·정렬용). 절감액이 아니라 "후보 개수"다 —
 * 금액은 지어내지 않는다(실제 과금 연동은 범위 밖). 신호까지만.
 *
 * @param instanceId     대상 인스턴스 id
 * @param instanceName   인스턴스 이름
 * @param type           기종
 * @param generatedAt    분석 시각
 * @param checks         분석기별 결과
 * @param candidateCount 낭비 후보 총수(파생값)
 */
public record InstanceFinOpsReport(Long instanceId, String instanceName, DbmsType type,
                                   LocalDateTime generatedAt, List<FinOpsCheck> checks,
                                   int candidateCount) {

    /** 후보 총수는 checks에서 파생 — 호출부가 세지 않도록 여기서 계산해 담는다. */
    public static InstanceFinOpsReport of(Long instanceId, String instanceName, DbmsType type,
                                          LocalDateTime generatedAt, List<FinOpsCheck> checks) {
        int count = checks.stream().mapToInt(c -> c.candidates().size()).sum();
        return new InstanceFinOpsReport(instanceId, instanceName, type, generatedAt,
                List.copyOf(checks), count);
    }
}
