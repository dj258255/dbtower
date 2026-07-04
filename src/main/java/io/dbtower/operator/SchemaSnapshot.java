package io.dbtower.operator;

import java.util.List;

/**
 * 한 인스턴스의 스키마 구조 스냅샷 (B7 Schema Diff) — 같은 역할의 두 장비를 비교해
 * "왜 저 장비만 다르지"(운영에만 있는 인덱스, 스테이징에만 있는 컬럼)를 추적하는 원천 데이터.
 *
 * 중요: 이것은 완벽한 DDL 재현이 아니라 diff에 필요한 구조 요약이다. 제약조건(FK/CHECK),
 * 트리거·뷰·시퀀스·권한·기본값·파티션 정의 등은 담지 않는다. 목적은 "복원 가능한 스키마 사본"이
 * 아니라 "두 스키마가 어디서 갈라지는지"를 컬럼·인덱스 수준에서 드러내는 것이다.
 *
 * 시스템 스키마(pg_catalog, information_schema, sys, mysql 등)는 제외하고 대상 dbName/스키마의
 * 사용자 테이블만 담는다. 대량 테이블 환경을 위해 각 Operator는 상위 N개(하드 상한)까지만 담는다 —
 * capped 여부를 tableCap/truncated로 정직하게 알린다.
 *
 * @param type       스냅샷을 뜬 기종 — 기종이 다르면 타입 표기가 달라 diff 해석에 주의가 필요하다
 * @param database   대상 데이터베이스/스키마 이름
 * @param tables     테이블(컬렉션) 구조 목록 (이름순)
 * @param truncated  대량 테이블 상한(tableCap)에 걸려 일부 테이블이 잘렸는지 — true면 diff가 부분 뷰
 * @param tableCap   적용된 테이블 상한(하드 상한)
 */
public record SchemaSnapshot(String type, String database, List<TableSchema> tables,
                             boolean truncated, int tableCap) {
}
