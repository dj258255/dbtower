package io.dbtower.review;

/**
 * 리뷰 요청이 승인/반려됐다는 이벤트 (운영 병목 아크 B2, R5). alert가 결과 카드를 보낸다.
 *
 * @param reviewId    리뷰 요청 id
 * @param instanceId  대상 인스턴스 id
 * @param approved    승인 여부(false = 반려)
 * @param decidedBy   결정한 ADMIN
 * @param comment     결정 코멘트(없으면 null)
 * @param onlineDdlHint 승인된 MySQL DDL이면 온라인 DDL 화면 안내(아니면 null) — 실행은 사람이
 */
public record ReviewDecidedEvent(long reviewId, long instanceId, boolean approved,
                                 String decidedBy, String comment, String onlineDdlHint) {
}
