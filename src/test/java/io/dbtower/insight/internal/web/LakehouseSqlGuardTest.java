package io.dbtower.insight.internal.web;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 15단계 — 장기 마트 질의의 SELECT 전용 가드. 마트는 read-only 서빙 계층이고 이 경로에
 * 쓰기는 존재하지 않는다. 가드는 방어선이지 SQL 파서가 아니다(최종 방어 = Metabase
 * 커넥션이 DuckLake를 read-only로 무는 구조) — 대표 우회 시도를 고정한다.
 */
class LakehouseSqlGuardTest {

    @Test
    void SELECT와_WITH는_통과한다() {
        assertNull(LakehouseController.rejectIfNotReadOnly(
                "SELECT instance_id, risk_flag FROM mart_capacity_forecast"));
        assertNull(LakehouseController.rejectIfNotReadOnly(
                "WITH t AS (SELECT * FROM fct_size_daily) SELECT * FROM t"));
        assertNull(LakehouseController.rejectIfNotReadOnly(
                "/* 주석 */ select 1"));
    }

    @Test
    void 쓰기와_DDL은_거부한다() {
        assertNotNull(LakehouseController.rejectIfNotReadOnly("DELETE FROM fct_size_daily"));
        assertNotNull(LakehouseController.rejectIfNotReadOnly("DROP TABLE mart_capacity_forecast"));
        assertNotNull(LakehouseController.rejectIfNotReadOnly(
                "SELECT 1; DROP TABLE mart_capacity_forecast")); // 문장 분리
        assertNotNull(LakehouseController.rejectIfNotReadOnly(
                "WITH t AS (SELECT 1) INSERT INTO x SELECT * FROM t")); // CTE 뒤 쓰기
        assertNotNull(LakehouseController.rejectIfNotReadOnly(
                "SELECT * FROM x WHERE 1=1 UNION ALL SELECT 1 FROM y; -- attach")); // 세미콜론
    }

    @Test
    void 주석으로_감싼_쓰기도_거부한다() {
        assertNotNull(LakehouseController.rejectIfNotReadOnly("-- select\nUPDATE x SET a=1"));
        assertNotNull(LakehouseController.rejectIfNotReadOnly("/* select */ create table x(a int)"));
    }

    @Test
    void 빈_질의는_거부한다() {
        assertNotNull(LakehouseController.rejectIfNotReadOnly(null));
        assertNotNull(LakehouseController.rejectIfNotReadOnly("   "));
    }
}
