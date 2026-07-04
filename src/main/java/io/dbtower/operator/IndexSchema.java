package io.dbtower.operator;

import java.util.List;

/**
 * 인덱스 하나의 구조 요약 (B7 Schema Diff).
 *
 * diff에 필요한 건 "어떤 컬럼들을, 유니크로 묶었나"까지다. 인덱스 종류(btree/hash),
 * 부분 인덱스 조건, 포함 컬럼(INCLUDE), 표현식 인덱스 세부는 요약에서 제외한다 —
 * "왜 저 장비만 다르지"를 추적하는 데 필요한 최소 공통 모델이라는 점을 명시.
 *
 * @param name     인덱스명 (기종별 PK 자동 이름 등 표기 그대로)
 * @param columns  인덱스 구성 컬럼 — 순서 있는 리스트(복합 인덱스의 선두 컬럼이 중요하므로 순서 보존).
 *                 표현식 인덱스처럼 컬럼으로 환원되지 않는 부분은 비어 있을 수 있다.
 * @param unique   유니크 여부
 */
public record IndexSchema(String name, List<String> columns, boolean unique) {
}
