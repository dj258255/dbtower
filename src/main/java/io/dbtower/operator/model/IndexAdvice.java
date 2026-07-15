package io.dbtower.operator.model;

/**
 * 인덱스 어드바이저 결과 (B3) — explain이 "인덱스가 없다"까지 지적한다면, 이것은
 * "이 인덱스를 만들면 플랜이 이렇게 바뀐다"를 실제 인덱스 생성 없이 시뮬레이션한 결과다.
 *
 * 핵심 가치는 대상 DB 상태를 전혀 바꾸지 않고(가상 인덱스) before/after 비용을 비교하는 것이다.
 * status는 3-값이며 복원 검증(A7)과 같은 정직성 원칙을 따른다 — "확인 못 함"을 "이득 있음"으로
 * 위장하지 않는다:
 * - ADVISED     : 가상 인덱스로 EXPLAIN을 다시 돌려 플랜 비용이 유의미하게 줄었다(만들 가치 있음).
 * - NO_BENEFIT  : 가상 인덱스를 만들어 봤지만 옵티마이저가 안 쓰거나 비용이 안 줄었다(만들어도 소용없음).
 * - UNSUPPORTED : 이 기종에서는 가상 인덱스 시뮬레이션을 못 한다(HypoPG 미지원/미설치, 후보 미지정 등).
 *                 통과가 아니라 "시뮬레이션 불가"다.
 *
 * beforePlan/afterPlan은 EXPLAIN (FORMAT JSON) 원문. before/afterCost는 최상위 플랜의 Total Cost.
 * UNSUPPORTED거나 비용을 못 뽑은 경우 plan/cost 필드는 null이다.
 *
 * @param status         ADVISED / NO_BENEFIT / UNSUPPORTED
 * @param detail         사람이 읽는 판정 근거(비용 변화율·미지원 사유 등)
 * @param suggestedIndex 시뮬레이션한 후보 인덱스 DDL(예: CREATE INDEX ON users (category))
 * @param beforePlan     원래 실행계획(JSON). 없으면 null
 * @param afterPlan      가상 인덱스 적용 후 실행계획(JSON). 없으면 null
 * @param beforeCost     원래 플랜의 Total Cost. 없으면 null
 * @param afterCost      가상 인덱스 적용 후 Total Cost. 없으면 null
 */
public record IndexAdvice(String status, String detail, String suggestedIndex,
                          String beforePlan, String afterPlan, Double beforeCost, Double afterCost) {

    public static final String ADVISED = "ADVISED";
    public static final String NO_BENEFIT = "NO_BENEFIT";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    public static IndexAdvice advised(String detail, String suggestedIndex,
                               String beforePlan, String afterPlan, double beforeCost, double afterCost) {
        return new IndexAdvice(ADVISED, detail, suggestedIndex, beforePlan, afterPlan, beforeCost, afterCost);
    }

    public static IndexAdvice noBenefit(String detail, String suggestedIndex,
                                 String beforePlan, String afterPlan, double beforeCost, double afterCost) {
        return new IndexAdvice(NO_BENEFIT, detail, suggestedIndex, beforePlan, afterPlan, beforeCost, afterCost);
    }

    public static IndexAdvice unsupported(String detail) {
        return new IndexAdvice(UNSUPPORTED, detail, null, null, null, null, null);
    }
}
