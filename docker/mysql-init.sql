-- DBTower 최소 권한 모니터링 계정 — MySQL (실측 근거: docs/operate/least-privilege.md)
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

-- 3차 아크 D-2: SHOW ENGINE INNODB STATUS(최근 데드락 1건)는 PROCESS 권한이 필요하다.
GRANT PROCESS ON *.* TO 'dbtower_monitor'@'%';

-- 2026-09-10 재실측(VERIFICATION 127절): 문서 실측(07-04) 뒤에 들어온 기능들이 읽는 테이블.
-- 하나씩 회수해 깨지는 것을 확인한 최소 집합이다.
-- wait-events, 심층 진단(explainAnalyze)의 대기 이벤트 차분
GRANT SELECT ON performance_schema.events_waits_summary_global_by_event_name TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.setup_instruments TO 'dbtower_monitor'@'%';
-- 통계 수집 건강 advisor, 최근 데드락 보조 지표
GRANT SELECT ON performance_schema.prepared_statements_instances TO 'dbtower_monitor'@'%';
-- 복제 상태(그룹 복제 토폴로지)
GRANT SELECT ON performance_schema.replication_group_members TO 'dbtower_monitor'@'%';
-- 인덱스 사용량(finops·파티션 보조). sys.schema_unused_indexes는 INVOKER 뷰라 원천 테이블 권한이 함께 있어야 한다
GRANT SELECT ON performance_schema.table_io_waits_summary_by_index_usage TO 'dbtower_monitor'@'%';
GRANT SELECT ON sys.schema_unused_indexes TO 'dbtower_monitor'@'%';
-- sessions의 블로킹 관계(blockedByPid). sys.innodb_lock_waits는 INVOKER 뷰라 뷰 SELECT만으로는
-- ERROR 1356이 난다: 원천 테이블 두 개와 뷰가 부르는 sys 함수 두 개의 EXECUTE가 전부 필요했다.
GRANT SELECT ON sys.innodb_lock_waits TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.data_locks TO 'dbtower_monitor'@'%';
GRANT SELECT ON performance_schema.data_lock_waits TO 'dbtower_monitor'@'%';
GRANT EXECUTE ON FUNCTION sys.format_statement TO 'dbtower_monitor'@'%';
GRANT EXECUTE ON FUNCTION sys.quote_identifier TO 'dbtower_monitor'@'%';
