package io.dbtower.review;

/**
 * 승인된 변경 티켓이 대상 DB에 실행됐거나 되돌려졌다는 이벤트. alert가 결과 카드를 보낸다(ReviewDecidedEvent와 같은 위임).
 *
 * @param reviewId     리뷰 요청 id
 * @param instanceId   대상 인스턴스 id
 * @param rolledBack   false면 실행, true면 되돌리기
 * @param actor        실행하거나 되돌린 ADMIN
 * @param affectedRows 대상 DB가 보고한 영향 행 수(되돌리기면 되돌린 행 수)
 */
public record ReviewExecutedEvent(long reviewId, long instanceId, boolean rolledBack, String actor, long affectedRows) {
}
