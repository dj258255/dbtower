package io.dbtower.operator.model;

import java.util.List;

/**
 * 콘솔 조회 결과 — 행 상한까지만 담고, 상한을 넘는 행이 더 있었는지를 truncated로 알린다.
 * 값은 JSON으로 그대로 내보낼 수 있는 형태(문자열·숫자·불리언·null)로 이미 변환돼 있다.
 */
public record QueryResult(List<ResultColumn> columns, List<List<Object>> rows, boolean truncated, long elapsedMs) {

    public int rowCount() {
        return rows.size();
    }
}
