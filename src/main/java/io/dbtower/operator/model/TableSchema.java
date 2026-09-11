package io.dbtower.operator.model;

import java.util.List;

/**
 * 테이블(또는 MongoDB 컬렉션) 하나의 구조 요약 (B7 Schema Diff).
 *
 * MongoDB처럼 스키마리스인 기종은 columns가 비어 있고 indexes만 채워진다 —
 * "컬럼 개념이 없어 컬렉션·인덱스 구조만" 비교한다는 뜻(MongoOperator.describeSchema 주석 참고).
 *
 * @param name        테이블/컬렉션명
 * @param columns     컬럼 목록(ordinalPosition 순). 스키마리스 기종은 빈 리스트
 * @param indexes     인덱스 목록
 * @param kind        {@link #TABLE} 또는 {@link #VIEW} — 스키마 트리가 둘을 나눠 보인다(150절). 뷰에는 인덱스·기본키가 없다
 * @param primaryKey  기본키 열(순서대로). 없거나 확보하지 못하면 빈 리스트
 * @param foreignKeys 이 테이블이 가진 외래키(가리키는 쪽만 — 가리켜지는 쪽은 다른 테이블의 목록에 있다).
 *                    구조 비교가 제약조건 변화를 보려면 스냅샷에 들어와야 한다(153절). 개념이 없는 기종은 빈 리스트
 */
public record TableSchema(String name, List<ColumnSchema> columns, List<IndexSchema> indexes,
                          String kind, List<String> primaryKey, List<ForeignKey> foreignKeys) {

    public static final String TABLE = "TABLE";
    public static final String VIEW = "VIEW";

    /** 종류·기본키를 다루지 않는 호출(어드바이저 입력·단위 테스트 등) — 테이블, 기본키·외래키 미상 */
    public TableSchema(String name, List<ColumnSchema> columns, List<IndexSchema> indexes) {
        this(name, columns, indexes, TABLE, List.of(), List.of());
    }

    /** 외래키를 모르는 호출(150절까지의 조립 경로) */
    public TableSchema(String name, List<ColumnSchema> columns, List<IndexSchema> indexes,
                       String kind, List<String> primaryKey) {
        this(name, columns, indexes, kind, primaryKey, List.of());
    }
}
