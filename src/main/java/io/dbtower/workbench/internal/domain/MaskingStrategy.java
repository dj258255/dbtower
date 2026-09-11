package io.dbtower.workbench.internal.domain;

/**
 * 마스킹 방식. FULL은 존재만 알린다. PARTIAL은 앞뒤 일부로 사람이 행을 구분할 수 있게 한다.
 * HASH는 같은 값이 같은 토큰이 돼, 원문을 보지 않고도 "같은 고객의 주문인가"를 셀 수 있게 한다.
 */
public enum MaskingStrategy {
    FULL,
    PARTIAL,
    HASH
}
