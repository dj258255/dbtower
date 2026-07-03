CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- 최소 권한 모니터링 계정 (실측 근거: docs/least-privilege.md)
-- LOGIN + pg_read_all_stats만으로 health/query-stats/slow-queries/table-stats/replication 전부 통과.
-- pg_read_all_stats가 없으면 pg_stat_statements의 query가 <insufficient privilege>로 마스킹된다(에러 없이 조용히 저하).
-- EXPLAIN 대상 테이블이 생기면 해당 테이블 SELECT를 추가로 부여할 것.
CREATE ROLE dbtower_monitor LOGIN PASSWORD 'dbtower1234';
GRANT pg_read_all_stats TO dbtower_monitor;
