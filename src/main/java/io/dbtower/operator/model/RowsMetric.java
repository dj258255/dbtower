package io.dbtower.operator.model;

/**
 * 누적 쿼리 통계({@link QueryStat#rowsExamined()})의 행 지표 자리에 기종이 실제로 담는 값.
 *
 * <p>필드 이름은 MySQL 기준("검사한 행")이지만 기종마다 얻을 수 있는 카운터가 달라 같은 자리에 다른 단위가 들어온다.
 * PostgreSQL pg_stat_statements.rows는 돌려주거나 바꾼 행, SQL Server는 논리 읽기 페이지, Oracle은 버퍼 읽기 블록이다.
 * 이 구분 없이 "스캔 행"이라 적으면 PostgreSQL에서 호출 수가 늘어난 것을 스캔 증가로 읽게 된다(VERIFICATION 131·132절).
 */
public enum RowsMetric {
    EXAMINED_ROWS("검사한 행"),
    RETURNED_ROWS("돌려주거나 바꾼 행"),
    LOGICAL_READS("논리 읽기(페이지)"),
    BUFFER_GETS("버퍼 읽기(블록)"),
    EXAMINED_DOCUMENTS("검사한 문서");

    private final String label;

    RowsMetric(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
