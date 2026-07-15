package io.dbtower.operator.model;

import java.util.List;

/**
 * 테이블 하나의 상세 정보 (심화 아크 3 — 테이블 상세 정보). 스키마 정보(DDL)·기본 통계·
 * 인덱스 정보(카디널리티 포함)를 한 번에 담아, 상세 패널과 문의 첨부의 원천이 된다.
 *
 * describeSchema(구조 요약·diff용)와 달리 진단 첨부용 풀 뷰다. 기종별 가용성이 달라 "값과 출처의 정직"을
 * 지킨다(레이턴시 백분위 D4a와 같은 원칙):
 * - ddl: MySQL=SHOW CREATE TABLE(NATIVE), Oracle=DBMS_METADATA(NATIVE),
 *   PostgreSQL/MSSQL=카탈로그 재구성(RECONSTRUCTED — 근사), MongoDB=컬렉션·인덱스 JSON.
 * - cardinality: MySQL=STATISTICS.CARDINALITY, Oracle=DISTINCT_KEYS(네이티브),
 *   PostgreSQL=선두 컬럼 n_distinct 추정, MSSQL/Mongo=null(미확보 정직).
 *
 * @param table        테이블/컬렉션명
 * @param engine       스토리지 엔진(InnoDB 등) — 개념이 없는 기종은 null
 * @param rowCount     대략 행수(통계 기반 추정, -1이면 미확보)
 * @param dataBytes    데이터 크기(-1 미확보)
 * @param indexBytes   인덱스 크기(-1 미확보)
 * @param avgRowBytes  평균 행 길이(-1 미확보)
 * @param createdAt    생성 시각 문자열(미확보 null)
 * @param ddl          CREATE TABLE 전문 또는 재구성 DDL(미지원 null)
 * @param ddlSource    NATIVE(엔진이 준 원문) / RECONSTRUCTED(카탈로그 재구성 근사) / UNSUPPORTED
 * @param indexes      인덱스 상세(타입·카디널리티 포함)
 * @param note         기종별 한계·출처 설명(추정치 라벨 등)
 */
public record TableDetail(String table, String engine, long rowCount, long dataBytes, long indexBytes,
                          long avgRowBytes, String createdAt, String ddl, DdlSource ddlSource,
                          List<IndexDetail> indexes, String note) {

    public enum DdlSource { NATIVE, RECONSTRUCTED, UNSUPPORTED }

    /**
     * 인덱스 하나의 상세 — 인덱스 정보 카드의 단위.
     *
     * @param cardinality 고유값 추정(선택도 판단 재료). null이면 해당 기종에서 미확보(정직)
     * @param type        BTREE/HASH 등 인덱스 타입(미확보 null)
     */
    public record IndexDetail(String name, List<String> columns, boolean unique, String type, Long cardinality) {
    }

    public static TableDetail unsupported(String table, String note) {
        return new TableDetail(table, null, -1, -1, -1, -1, null, null, DdlSource.UNSUPPORTED, List.of(), note);
    }
}
