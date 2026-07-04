package io.dbtower.operator;

/**
 * 활성 세션 한 건 (B2) — "지금 누가 무엇을 하고, 누가 누구를 막고 있나".
 *
 * 대기 이벤트(WaitEvent)가 "무엇을 기다렸나의 집계"라면, 이것은 "지금 이 순간의 개별 세션"이다.
 * blockedByPid가 채워진 행이 곧 블로킹 트리의 잎 — DBA가 장애 시 가장 먼저 보는 값이다.
 *
 * 기종마다 pid의 의미가 다르다(같은 스키마로 뭉개지 않고 주석으로 차이를 드러낸다):
 * - PostgreSQL: pid = pg_stat_activity.pid (백엔드 프로세스 id). blockedByPid = pg_blocking_pids(pid) 첫 값
 * - MySQL: pid = processlist id. blockedByPid = sys.innodb_lock_waits의 blocking_pid
 * - SQL Server: pid = session_id(SPID). blockedByPid = blocking_session_id(0이면 null)
 * - Oracle: pid = v$session.sid. blockedByPid = blocking_session(sid). kill 시 serial#을 재조회한다
 * - MongoDB: pid = currentOp의 opid. blockedByPid = null (문서 단위 락 모델이라 대개 N/A)
 *
 * @param pid          기종별 세션/프로세스/opid (kill의 대상 식별자)
 * @param user         접속 사용자/로그인명 (없으면 null)
 * @param state        세션 상태 (active/idle in transaction/running 등 — 기종 표기 그대로)
 * @param waitEvent    지금 기다리는 이벤트/락 (없으면 null)
 * @param blockedByPid 나를 막고 있는 세션의 pid (막힘 없으면 null)
 * @param query        실행 중이거나 마지막 쿼리 (최대 2000자로 절단)
 * @param elapsedMs    현재 쿼리/호출 경과 시간(ms)
 */
public record SessionInfo(long pid, String user, String state, String waitEvent,
                          Long blockedByPid, String query, double elapsedMs) {
}
