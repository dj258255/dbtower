package io.dbtower.operator.model;

/**
 * 테이블 블로트/VACUUM 신호 (PostgreSQL 전용, C-2) — pg_stat_user_tables의 컬럼으로 판정한다.
 *
 * autovacuum이 죽은 튜플(UPDATE/DELETE의 옛 버전)을 못 따라가면 테이블이 부풀어(bloat) 스캔이 느려지고
 * 디스크를 낭비한다. deadTuples는 통계 추정치라 실측 블로트가 아니다(ESTIMATED) — pgstattuple 같은
 * 실측은 이번 범위 밖. 읽기 전용.
 *
 * @param tableName        스키마.테이블
 * @param deadTuples       죽은 튜플 수(추정 — n_dead_tup)
 * @param liveTuples       살아있는 튜플 수(추정 — n_live_tup)
 * @param deadRatio        deadTuples / (liveTuples + deadTuples). 높을수록 블로트 의심
 * @param lastAutovacuum   마지막 autovacuum 시각(ISO 문자열, null이면 기록 없음)
 * @param modsSinceAnalyze 마지막 ANALYZE 이후 변경 튜플 수(통계 노후 신호 — n_mod_since_analyze)
 */
public record TableBloat(String tableName, long deadTuples, long liveTuples, double deadRatio,
                         String lastAutovacuum, long modsSinceAnalyze) {
}
