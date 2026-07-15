package io.dbtower.operator.internal;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기종 정확성·타임존 하드닝(WS-C) 단위 검증.
 *
 * DB 접속이 필요 없는 순수 로직만 뽑아 검증한다 — 슬로우쿼리 SQL의 마이크로초 보정(C-1),
 * 레이턴시 top 쿼리의 digest 접기(C-2), Oracle 스키마 필터 절 생성(C-4), PHV=0 스킵(C-5).
 * C-3(PG 클러스터 집계)·C-6(UTC 고정)은 SQL 텍스트/부트스트랩이라 라이브·기동 경로에서 확인한다.
 */
class WsCHardeningTest {

    // ---------- C-1: MySQL 슬로우쿼리 마이크로초 보정 ----------

    @Test
    void C1_슬로우쿼리_SQL이_초절삭_대신_마이크로초를_더한다() {
        // TIME_TO_SEC만 *1000하면 1초 미만이 0ms로 뭉개진다 — MICROSECOND 보정항이 있어야 한다.
        assertThat(MySqlOperator.SLOW_QUERIES_SQL)
                .contains("TIME_TO_SEC(query_time) * 1000")
                .contains("MICROSECOND(query_time) / 1000");
    }

    // ---------- C-2: MySQL 동일 DIGEST 멀티스키마 접기 ----------

    @Test
    void C2_레이턴시_top쿼리가_DIGEST로_접힌다() {
        // (SCHEMA_NAME,DIGEST) 중복 행이 GROUP BY DIGEST로 digest당 1행이 되어 스냅샷 키 충돌을 없앤다.
        assertThat(MySqlOperator.LATENCY_TOP_SQL)
                .contains("GROUP BY DIGEST")
                .contains("ORDER BY SUM(SUM_TIMER_WAIT)")   // 스키마 합산 부하로 정렬
                .contains("MAX(DIGEST_TEXT)");              // 텍스트는 대표값
    }

    // ---------- C-4: Oracle 스키마 필터 절 ----------

    @Test
    void C4_앱스키마_미지정이면_시스템스키마만_제외한다() {
        String clause = OracleOperator.schemaFilterClause(false);
        assertThat(clause).isEqualTo(
                "parsing_schema_name NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'SYSMAN')");
        // 모니터 CURRENT_SCHEMA 고정이 아니어야 한다 — 앱 SQL 전멸 회귀 방지.
        assertThat(clause).doesNotContain("CURRENT_SCHEMA");
    }

    @Test
    void C4_앱스키마_지정이면_바인딩_파라미터로_그_스키마만() {
        assertThat(OracleOperator.schemaFilterClause(true))
                .isEqualTo("parsing_schema_name = ?");   // 값은 바인딩(=?)으로 안전하게
    }

    // ---------- C-5: Oracle PHV=0 허위 플립 방지 ----------

    @Test
    void C5_PHV_0은_계획_미포착이라_empty() {
        assertThat(OracleOperator.planShapeForPhv(0)).isEmpty();
    }

    @Test
    void C5_PHV_양수는_shape_식별자() {
        assertThat(OracleOperator.planShapeForPhv(1234567890L))
                .isEqualTo(Optional.of("PHV:1234567890"));
    }
}
