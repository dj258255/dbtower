package io.dbtower.operator.model;

/**
 * 정규화(digest)된 쿼리 하나의 누적 통계.
 * 소스는 기종마다 다르다 — MySQL: performance_schema digest, PostgreSQL: pg_stat_statements, MSSQL: DMV.
 * calls/totalTimeMs는 서버 기동 이후 누적 카운터라, 시점 비교는 두 스냅샷의 차분으로 계산한다.
 *
 * <p>plan은 MongoDB만 채운다(system.profile이 계획 요약을 함께 저장하는 유일한 소스 — IXSCAN/COLLSCAN).
 * 다른 기종의 통계 뷰에는 계획 정보가 없으므로 null(미확보를 위장하지 않는다).
 */
public record QueryStat(String queryId, String queryText, long calls, double totalTimeMs, long rowsExamined,
                        String plan) {

    /** 계획 정보가 없는 기종을 위한 간편 생성자 — plan=null. */
    public QueryStat(String queryId, String queryText, long calls, double totalTimeMs, long rowsExamined) {
        this(queryId, queryText, calls, totalTimeMs, rowsExamined, null);
    }
}
