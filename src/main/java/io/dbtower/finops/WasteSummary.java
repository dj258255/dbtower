package io.dbtower.finops;

/**
 * 낭비 신호 요약 공개 뷰 (운영 병목 아크 B5 재료) — 월간 리포트가 인스턴스별 낭비 후보 수를
 * 읽기 위한 최소 공개 타입. 상세 후보 목록은 finops 내부에 두고 집계만 노출한다.
 *
 * @param instanceId     인스턴스 id
 * @param candidateCount 낭비 후보 총수(미사용/중복 인덱스·오버프로비저닝 등)
 * @param supported      낭비 분석 지원 기종 여부(Oracle 인덱스 통계 등 미지원이면 false)
 */
public record WasteSummary(Long instanceId, int candidateCount, boolean supported) {
}
