/**
 * 스키마·데이터 변경 리뷰 게이트 (운영 병목 아크 B2) — 개발자의 DDL/DML 변경 요청을 규칙으로 자동 판정하고, AI 1차 소견을
 * 붙이고, ADMIN이 승인/반려하며, 전 과정을 감사에 남긴다.
 *
 * 정체성: 게이트는 상태의 단일 권위다. 승인된 티켓의 실행은 워크벤치 실행 계층이 하지만, 실행권과 결과 전이는
 * {@link io.dbtower.review.ChangeTicketGate}의 조건부 UPDATE로만 얻고 남긴다(승인되지 않은 SQL·편집된 SQL은 실행 경로에
 * 들어갈 수 없다). 원칙: "관제탑은 대상 DB에 임의 변경을 실행하지 않는다 — 승인된 티켓만 실행한다".
 *
 * 의존 방향: review -> registry·operator·analysis(규칙·AI·마스킹). workbench -> review(게이트)는 한 방향이다.
 * 카드 발송은 alert에 이벤트로 위임한다(ReviewSubmittedEvent·ReviewDecidedEvent·ReviewExecutedEvent) — review가 alert 내부를
 * 참조하지 않아 순환이 없다.
 */
package io.dbtower.review;
