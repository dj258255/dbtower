package io.dbtower.operator.model;

/**
 * 정규화(digest)된 쿼리 하나의 누적 통계.
 * 소스는 기종마다 다르다 — MySQL: performance_schema digest, PostgreSQL: pg_stat_statements, MSSQL: DMV.
 * calls/totalTimeMs는 서버 기동 이후 누적 카운터라, 시점 비교는 두 스냅샷의 차분으로 계산한다.
 */
public record QueryStat(String queryId, String queryText, long calls, double totalTimeMs, long rowsExamined) {
}
