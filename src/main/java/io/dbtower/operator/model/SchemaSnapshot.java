package io.dbtower.operator.model;

import java.util.List;

/**
 * 한 인스턴스의 스키마 구조 스냅샷 (B7 Schema Diff) — 같은 역할의 두 장비를 비교해
 * "왜 저 장비만 다르지"(운영에만 있는 인덱스, 스테이징에만 있는 컬럼)를 추적하는 원천 데이터.
 *
 * 복원 가능한 DDL 사본으로 오인하지 않도록 비교 범위를 한정한다. 열·인덱스·외래키와
 * 확보한 CHECK·트리거 정의를 비교하며, 뷰 본문·시퀀스·권한·기본값·파티션 정의는 담지 않는다.
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
