package io.dbtower.operator;

/** 테이블 하나의 크기 통계. 행수는 기종별 추정치(통계 기반)라 정확한 COUNT와 다를 수 있다. */
public record TableStat(String tableName, long rowCount, long dataBytes, long indexBytes) {
}
