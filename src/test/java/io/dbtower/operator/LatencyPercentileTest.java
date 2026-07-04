package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 레이턴시 백분위 (D4a) — 네 종류 source(NATIVE/COMPUTED/ESTIMATED/UNSUPPORTED)의 정직성 규약,
 * Mongo nearest-rank 계산, PG 정규분포 근사 산식, MSSQL/Oracle 미지원 분기를 커넥션 없이 못 박는다.
 * 실제 DB에서 나오는 값(QUANTILE 컬럼·profile 계산)은 라이브 검증으로 확인한다.
 */
class LatencyPercentileTest {

    @Test
    void unsupported_안내행은_p95_p99가_null이고_사유를_담는다() {
        LatencyPercentile u = LatencyPercentile.unsupported("Oracle 백분위 원자료 없음");
        assertEquals(LatencyPercentile.UNSUPPORTED, u.source());
        assertNull(u.p95Ms(), "미지원이면 p95는 null이어야 한다 — 0으로 위장 금지");
        assertNull(u.p99Ms());
        assertNull(u.queryId());
        assertTrue(u.queryText().contains("원자료 없음"));
    }

    @Test
    void mongo_nearest_rank_백분위는_정렬표본에서_정확한_값을_고른다() {
        // 1..100 오름차순 → p95=95, p99=99 (nearest-rank: rank=ceil(p/100*n), idx=rank-1)
        List<Double> samples = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            samples.add((double) i);
        }
        assertEquals(95.0, MongoOperator.percentile(samples, 95));
        assertEquals(99.0, MongoOperator.percentile(samples, 99));
        assertEquals(100.0, MongoOperator.percentile(samples, 100));
    }

    @Test
    void mongo_백분위는_표본이_적거나_없을때_정직하게_처리한다() {
        assertNull(MongoOperator.percentile(List.of(), 95), "표본이 없으면 null — 0이 아니다");
        // 표본 하나면 어떤 백분위든 그 값
        assertEquals(7.0, MongoOperator.percentile(List.of(7.0), 95));
        // 표본 두 개: p95 rank=ceil(0.95*2)=2 → 큰 값, p50 rank=ceil(0.5*2)=1 → 작은 값
        List<Double> two = new ArrayList<>(List.of(3.0, 9.0));
        assertEquals(9.0, MongoOperator.percentile(two, 95));
        assertEquals(3.0, MongoOperator.percentile(two, 50));
    }

    @Test
    void pg_근사는_mean_더하기_z곱하기stddev이고_음수는_클램프된다() {
        // p95 z=1.645, p99 z=2.326 (표준정규 경계)
        assertEquals(1.645, PostgresOperator.Z95);
        assertEquals(2.326, PostgresOperator.Z99);
        // mean=10, stddev=2 → p95 ≈ 10 + 1.645*2 = 13.29
        assertEquals(13.29, PostgresOperator.estimate(10, 2, PostgresOperator.Z95));
        // mean=10, stddev=2 → p99 ≈ 10 + 2.326*2 = 14.65 (반올림)
        assertEquals(14.65, PostgresOperator.estimate(10, 2, PostgresOperator.Z99));
        // 표준편차가 0이면 근사=평균 (분산 없음)
        assertEquals(10.0, PostgresOperator.estimate(10, 0, PostgresOperator.Z95));
        // 음수 방어
        assertEquals(0.0, PostgresOperator.estimate(-100, 1, PostgresOperator.Z95));
    }

    @Test
    void mssql와_oracle은_백분위_원자료가_없어_UNSUPPORTED로_정직하게_보고한다() {
        // 두 기종은 AbstractJdbcOperator 기본 UNSUPPORTED — 커넥션을 열지 않고 즉시 반환한다
        DatabaseInstance mssql = new DatabaseInstance(
                "ms", DbmsType.MSSQL, "127.0.0.1", 1433, "app", "sa", "pw");
        List<LatencyPercentile> ms = new MsSqlOperator(mssql, null, null).latencyPercentiles(20);
        assertEquals(1, ms.size());
        assertEquals(LatencyPercentile.UNSUPPORTED, ms.get(0).source());
        assertNull(ms.get(0).p95Ms());
        assertTrue(ms.get(0).queryText().contains("MSSQL"), ms.get(0).queryText());

        DatabaseInstance oracle = new DatabaseInstance(
                "or", DbmsType.ORACLE, "127.0.0.1", 1521, "FREEPDB1", "system", "pw");
        List<LatencyPercentile> or = new OracleOperator(oracle, null, null).latencyPercentiles(20);
        assertEquals(1, or.size());
        assertEquals(LatencyPercentile.UNSUPPORTED, or.get(0).source());
        assertTrue(or.get(0).queryText().contains("ORACLE"), or.get(0).queryText());
    }
}
