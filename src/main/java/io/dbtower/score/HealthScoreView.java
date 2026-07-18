package io.dbtower.score;

/**
 * 헬스 스코어 공개 뷰 (운영 병목 아크 B5 재료) — 다른 모듈(월간 리포트)이 인스턴스 건강을
 * 프로그램적으로 읽기 위한 최소 공개 타입. 상세 신호 기여도는 score 내부에 두고 요약만 노출한다.
 *
 * @param instanceId 인스턴스 id
 * @param score      0~100 종합 점수
 * @param grade      A~F 등급
 * @param down       현재 다운(수집 실패) 여부
 */
public record HealthScoreView(Long instanceId, int score, String grade, boolean down) {
}
