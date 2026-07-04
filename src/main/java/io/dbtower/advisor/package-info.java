/**
 * 자동 점검 (Phase D2 Advisors) — Percona PMM Advisors 모델의 축소판.
 *
 * operations.md·least-privilege.md의 실측 운영 규칙을 코드 Advisor로 옮겨, 일일 스윕과
 * 온디맨드 REST로 5기종 인스턴스를 배경 점검한다. 각 Advisor는 "무엇이 위험한지(심각도·설명)"와
 * "고치는 법(권고)"까지 돌려준다.
 *
 * 정체성 가드레일(읽고 조언만): 모든 Advisor는 기존 operator 조회 메서드
 * (parameters·tableStats·describeSchema·queryStats)의 결과만 판정한다 — 대상 DB를 바꾸지 않고,
 * operator 인터페이스에 새 메서드를 더하지 않는다. 적용 불가 기종은 UNSUPPORTED로 정직하게 표기한다
 * (통과 위장 금지). registry/insight/operator에 대한 의존은 한 방향뿐이라 모듈 순환이 없다.
 */
package io.dbtower.advisor;
