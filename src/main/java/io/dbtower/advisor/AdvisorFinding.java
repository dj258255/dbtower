package io.dbtower.advisor;

/**
 * Advisor 지적 하나 — "무엇이 위험한지"와 "고치는 법"을 함께 담는다(PMM Advisors의 핵심 가치).
 *
 * recommendation은 항상 권고일 뿐이다 — DBTower는 대상 DB를 스스로 바꾸지 않는다(정체성 가드레일).
 *
 * @param severity       심각도(색 그룹핑용)
 * @param title          한 줄 요약(무엇이 문제인가)
 * @param detail         근거 — 어떤 값/구조를 보고 판정했는지(정직한 수치 포함)
 * @param recommendation 고치는 법(권고). 실행은 사람이 한다
 */
public record AdvisorFinding(Severity severity, String title, String detail, String recommendation) {
}
