-- DBTower 최소 권한 모니터링 계정 — MySQL (실측 근거: docs/least-privilege.md)
--
-- 주의: docker-compose.yml은 이 파일을 마운트하지 않는다 (compose 무수정 정책).
-- 수동 실행:
--   docker exec -i dbtower-mysql mysql -uroot -pdbtower1234 < docker/mysql-init.sql
-- (신규 볼륨에서 자동 실행하려면 /docker-entrypoint-initdb.d/에 마운트하면 되지만,
--  기존 mysql-data 볼륨에는 어차피 적용되지 않으므로 수동 실행을 기본으로 한다)

CREATE USER IF NOT EXISTS 'dbtower_monitor'@'%' IDENTIFIED BY 'dbtower1234';

-- 대상 스키마 SELECT: 기본 스키마 접속(health) + information_schema.TABLES 노출(table-stats) + EXPLAIN
GRANT SELECT ON sample.* TO 'dbtower_monitor'@'%';

-- query-stats: 다이제스트 요약 테이블 하나면 충분 (performance_schema.* 전체 불요, PROCESS 불요)
GRANT SELECT ON performance_schema.events_statements_summary_by_digest TO 'dbtower_monitor'@'%';
-- 2차 아크 B-1: 구간 p95(NATIVE_WINDOWED)는 히스토그램 두 스냅샷 차분이라 이 뷰 읽기 권한이 필요하다.
-- 권한이 없으면 코드가 조용히 누적값(NATIVE)으로 폴백한다(정직한 열화) — 최소권한 문서에 함께 기록.
GRANT SELECT ON performance_schema.events_statements_histogram_by_digest TO 'dbtower_monitor'@'%';

-- slow-queries: log_output=TABLE 전제 (docker-compose 설정)
GRANT SELECT ON mysql.slow_log TO 'dbtower_monitor'@'%';

-- replication: SHOW REPLICA STATUS는 REPLICATION CLIENT, 폴백 SHOW REPLICAS는 REPLICATION SLAVE
GRANT REPLICATION CLIENT, REPLICATION SLAVE ON *.* TO 'dbtower_monitor'@'%';
