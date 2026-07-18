/**
 * 스키마 변경 리뷰 게이트 (운영 병목 아크 B2) — 개발자의 DDL/대량 DML 배포 전 리뷰 요청을
 * 규칙으로 자동 판정하고, AI 1차 소견을 붙이고, ADMIN이 승인/반려하며, 전 과정을 감사에 남긴다.
 *
 * 정체성: 판정·승인·기록까지가 이 게이트의 몫이고, 승인된 변경의 실행은 하지 않는다(기존 gh-ost
 * 경로 또는 사람). 그래서 "관제탑은 대상 DB에 임의 DDL을 실행하지 않는다" 원칙과 충돌하지 않는다.
 *
 * 의존 방향: review -> registry·operator·analysis(규칙·AI·마스킹). 카드 발송은 alert에 이벤트로
 * 위임한다(ReviewSubmittedEvent·ReviewDecidedEvent) — review가 alert 내부를 참조하지 않아 순환이 없다.
 */
package io.dbtower.review;
