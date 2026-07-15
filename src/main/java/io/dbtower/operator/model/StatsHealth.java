package io.dbtower.operator.model;

/**
 * 쿼리 통계 수집 자체의 건강 상태 (심화 아크 5) — "수집이 조용히 거짓말하고 있지 않은가"를 실측한다.
 *
 * <p>레퍼런스 밋업의 Lessons Learned 대응: MySQL digest 테이블이 가득 차면 신규 쿼리 통계가 소실돼
 * 신규 쿼리 감지·회귀 감지가 눈이 멀고, Prepared Statement는 EXECUTE 문으로만 집계돼 Top Query에
 * 익명 부하로만 보인다(2026-07-15 데모 실측). PostgreSQL은 포화 대신 evict(dealloc)가 일어나
 * 저빈도 쿼리의 시점 비교 신뢰도가 떨어진다.
 *
 * <p>필드 의미는 기종별로 다르다 — note에 소스를 명시하고, 미확보 수치는 -1(0으로 위장 금지).
 * <ul>
 *   <li>MySQL: statsRows=digest 테이블 행수, statsLimit=performance_schema_digests_size,
 *       lostOrEvicted=Performance_schema_digest_lost(소실 — 이미 잃었다),
 *       psInstances/psExecutions=prepared_statements_instances 행수/COUNT_EXECUTE 합</li>
 *   <li>PostgreSQL: statsRows=pg_stat_statements 행수, statsLimit=pg_stat_statements.max,
 *       lostOrEvicted=pg_stat_statements_info.dealloc(evict — 밀려났다), ps*=-1(해당 사각 없음)</li>
 * </ul>
 */
public record StatsHealth(long statsRows, long statsLimit, long lostOrEvicted,
                          long psInstances, long psExecutions, boolean supported, String note) {

    public static StatsHealth unsupported(String note) {
        return new StatsHealth(-1, -1, -1, -1, -1, false, note);
    }

    /** 포화율(%) — 상한/행수 미확보면 -1. */
    public double usedPct() {
        if (statsRows < 0 || statsLimit <= 0) {
            return -1;
        }
        return Math.round(statsRows * 10000.0 / statsLimit) / 100.0;
    }
}
