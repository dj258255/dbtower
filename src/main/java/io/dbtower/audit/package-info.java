/**
 * 감사 로그 (Phase A6) — 상태 변경·진단 실행(/api/** POST/PUT/DELETE)과 로그인 성공/실패를
 * "누가(principal/role) 언제(occurred_at) 무엇을(action) 했고 결과가 어땠나(outcome/duration)"로 남긴다.
 * 다른 내부 모듈에 의존하지 않는다 — 주체 식별은 SecurityContextHolder(스프링 시큐리티 코어)에서 직접.
 */
package io.dbtower.audit;
