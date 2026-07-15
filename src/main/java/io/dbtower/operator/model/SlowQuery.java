package io.dbtower.operator.model;

/**
 * 느린 쿼리 한 건. MySQL은 slow_log 테이블, PostgreSQL/MSSQL은 통계 기반(평균 소요시간),
 * MongoDB는 system.profile로 수집한다.
 *
 * <p>기종별로 확보 가능한 필드가 달라 "값과 출처의 정직" 원칙을 따른다: 미확보 필드는 문자열이면 null,
 * 수치면 -1로 두고 화면에서 "—"로 표기한다(0으로 위장하지 않는다). userHost/lockMs/rowsSent는 MySQL
 * slow_log가, planSummary(IXSCAN/COLLSCAN)는 MongoDB profiler가 채운다.
 */
public record SlowQuery(String queryText, double elapsedMs, long rowsExamined, String capturedAt,
                        String userHost, double lockMs, long rowsSent, String planSummary) {

    /** 확장 필드가 없는 기종을 위한 간편 생성자 — userHost=null, lockMs/rowsSent=-1, planSummary=null. */
    public SlowQuery(String queryText, double elapsedMs, long rowsExamined, String capturedAt) {
        this(queryText, elapsedMs, rowsExamined, capturedAt, null, -1, -1, null);
    }
}
