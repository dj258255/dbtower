/**
 * 웹훅 push 채널 두 갈래:
 * - 회귀 자동 감지 — 시점 비교를 주기 실행해 4규칙(신규·급증·회귀·행수 폭증)을 판정해 미는 알림(플랫폼 트리거).
 * - DB팀 문의 — 사람이 분석 결과(쿼리·플랜·규칙 지적·AI 분석)를 DB팀에 보내는 문의(사람 트리거).
 * 둘 다 같은 WebhookNotifier를 공유한다. 문의를 여기 둔 이유는 InquiryService 주석 참고(insight 순환 회피).
 */
package io.dbtower.alert;
