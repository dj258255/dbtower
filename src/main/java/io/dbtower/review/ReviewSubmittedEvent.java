package io.dbtower.review;

import java.util.List;

/**
 * 스키마 변경 리뷰 요청이 접수됐다는 이벤트 (운영 병목 아크 B2, R5). alert 모듈이 이 이벤트를
 * 받아 Discord 카드를 보낸다 — review는 alert 내부(WebhookNotifier)를 직접 참조하지 않고
 * (Modulith 순환 회피), 카드 발송이라는 alert의 책임에 이벤트로 위임한다.
 *
 * maskedSql은 외부 발신용 마스킹본이다(리터럴 값 제거) — review가 마스킹까지 끝내 넘긴다.
 *
 * @param reviewId     리뷰 요청 id(카드의 승인 딥링크에 쓰인다)
 * @param instanceId   대상 인스턴스 id
 * @param requester    제출자
 * @param maskedSql    마스킹된 대상 SQL
 * @param findings     규칙 지적 목록
 * @param aiOpinion    AI 1차 소견(없으면 null)
 * @param parseLimited 파싱 한계로 판정이 불완전할 수 있는지
 */
public record ReviewSubmittedEvent(long reviewId, long instanceId, String requester,
                                   String maskedSql, List<String> findings, String aiOpinion,
                                   boolean parseLimited) {
}
