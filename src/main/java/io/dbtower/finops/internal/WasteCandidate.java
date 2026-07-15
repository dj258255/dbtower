package io.dbtower.finops.internal;

import io.dbtower.advisor.Severity;

/**
 * 낭비 후보 하나 (D6 FinOps) — "무엇이 낭비 신호인지"와 "그렇게 본 근거", "사람이 무엇을 검토할지"를 담는다.
 *
 * <b>정직 규약:</b> 절감액(달러)을 지어내지 않는다. 실제 클라우드 과금 연동은 자격증명이 필요해 범위 밖이고,
 * 여기서는 "절감 가능 신호"까지만 낸다. recommendation은 항상 권고일 뿐 — 실행(인덱스 삭제·rightsizing)은
 * 사람이 한다. severity는 후보의 상대적 주목도이지 금액이 아니다.
 *
 * @param kind           낭비 종류(웹 콘솔 그룹핑)
 * @param severity       주목도(색 그룹핑용 — advisor.Severity 재사용)
 * @param target         대상(테이블.인덱스 / 파라미터 등)
 * @param evidence       근거 — 어떤 실측값을 보고 후보로 판정했는지(정직한 수치·출처)
 * @param recommendation 검토 권고. 실행은 사람이 한다
 */
public record WasteCandidate(WasteKind kind, Severity severity, String target,
                             String evidence, String recommendation) {
}
