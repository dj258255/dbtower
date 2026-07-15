package io.dbtower.operator.model;

import java.util.List;

/**
 * 테이블(또는 MongoDB 컬렉션) 하나의 구조 요약 (B7 Schema Diff).
 *
 * MongoDB처럼 스키마리스인 기종은 columns가 비어 있고 indexes만 채워진다 —
 * "컬럼 개념이 없어 컬렉션·인덱스 구조만" 비교한다는 뜻(MongoOperator.describeSchema 주석 참고).
 *
 * @param name     테이블/컬렉션명
 * @param columns  컬럼 목록(ordinalPosition 순). 스키마리스 기종은 빈 리스트
 * @param indexes  인덱스 목록
 */
public record TableSchema(String name, List<ColumnSchema> columns, List<IndexSchema> indexes) {
}
