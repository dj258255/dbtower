package io.dbtower.operator;

/**
 * 컬럼 하나의 구조 요약 (B7 Schema Diff).
 *
 * 완벽한 DDL 재현이 아니라 "두 스키마를 비교할 때 필요한 최소 정보"만 담는다 —
 * 기본값·코멘트·collation·생성표현식 등은 diff의 신호가 아니라 노이즈라 의도적으로 뺐다.
 *
 * type은 기종 고유 표기 그대로다(예: PG 'character varying', MySQL 'varchar(255)',
 * Oracle 'VARCHAR2'). 기종이 다르면 같은 논리 타입도 문자열이 달라 diff에서 "변경"으로 보일 수 있어,
 * 기종이 다른 두 인스턴스를 비교할 때는 SchemaDiffService가 경고를 함께 싣는다.
 *
 * @param name             컬럼명
 * @param type             기종 고유 타입 표기 (번역·정규화하지 않음)
 * @param nullable         NULL 허용 여부
 * @param ordinalPosition  테이블 내 컬럼 순서(1부터) — 순서 차이 자체는 diff 대상이 아니고 표시·정렬용
 */
public record ColumnSchema(String name, String type, boolean nullable, int ordinalPosition) {
}
