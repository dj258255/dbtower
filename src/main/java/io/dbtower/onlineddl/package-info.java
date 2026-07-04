/**
 * 온라인 스키마 변경 (Phase B4) — 대형 테이블 ALTER를 락 최소화로 수행하는 gh-ost를 오케스트레이션한다.
 *
 * 실제 테이블 구조를 바꾸는 유일한 파괴적 기능이라 안전·정직이 최우선이다:
 * - 1차 검증은 항상 dry-run(gh-ost의 기본 noop 모드) — 실제 변경(--execute)은 명시적 옵션.
 * - gh-ost는 MySQL 전용이므로 나머지 기종은 UNSUPPORTED로 정직하게 보고한다(성공 위장 금지).
 * - 비밀번호는 argv에 절대 싣지 않는다(OnlineDdlCommands 주석의 한계 설명 참고).
 * - MCP로는 노출하지 않는다 — 에이전트가 스키마 변경을 실행하는 건 위험하기 때문(McpProtocolHandler 주석).
 */
package io.dbtower.onlineddl;
