package io.dbtower.operator;

/** 느린 쿼리 한 건. MySQL은 slow_log 테이블, PostgreSQL/MSSQL은 통계 기반(평균 소요시간)으로 수집한다. */
public record SlowQuery(String queryText, double elapsedMs, long rowsExamined, String capturedAt) {
}
