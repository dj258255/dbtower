/**
 * DB SLO / 에러 버짓 (Phase D4) — Google SRE·DBRE 모델.
 *
 * "인프라 지표(CPU 사용률)가 아니라 사용자 경험 지표"라는 SRE 원칙을 따른다: 레이턴시(핵심 쿼리 p95/p99)와
 * 가용성(health up 비율)을 SLI로 정의하고, 목표(SLO) 대비 에러 버짓 소진율·번인 레이트를 계산한다.
 *
 * 레이턴시 SLI는 D4a(operator.latencyPercentiles)를 재사용한다 — 기종별 원자료 가용성이 달라 값이 아니라
 * 그 값의 출처(source)를 정직하게 표기하고, 백분위 원자료가 없는 기종(SQL Server/Oracle)은 평균 레이턴시로
 * 폴백하며 그 사실을 응답에 명시한다. 가용성 SLI는 이 모듈의 헬스 샘플러가 쌓은 이력(HealthSample)으로 계산한다.
 *
 * 읽고 판단만 한다(Phase D 가드레일) — 대상 DB를 바꾸지 않고, 못 하는 기종은 정직하게 폴백/부족을 표기한다.
 */
package io.dbtower.slo;
