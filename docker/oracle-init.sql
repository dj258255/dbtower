-- 최초 기동 시 1회 실행 (gvenzl 이미지가 SYSDBA로 실행)
-- DBTower가 붙는 sample 계정에 모니터링·백업 권한 부여:
--   V$SQL 등 딕셔너리 뷰 조회, Data Pump 디렉터리 읽기/쓰기
ALTER SESSION SET CONTAINER = FREEPDB1;

GRANT SELECT_CATALOG_ROLE TO sample;
GRANT SELECT ANY DICTIONARY TO sample;
GRANT READ, WRITE ON DIRECTORY DATA_PUMP_DIR TO sample;

-- 샘플 데이터 시드 (인덱스는 PK 없음: TABLE ACCESS FULL 시나리오 재현용)
CREATE TABLE sample.users AS
SELECT level AS id,
       'user' || level AS name,
       MOD(level, 10) AS category,
       MOD(level, 1000) AS price
FROM dual CONNECT BY level <= 20000;

-- num_rows(테이블 통계)가 채워지도록 옵티마이저 통계 수집
EXEC DBMS_STATS.GATHER_TABLE_STATS('SAMPLE', 'USERS');

-- 최소 권한 모니터링 계정 (실측 근거: docs/least-privilege.md)
-- CREATE SESSION + SELECT_CATALOG_ROLE(V$SQL, V$DATABASE) + 대상 테이블 READ(EXPLAIN PLAN용).
-- EXPLAIN PLAN 자체는 추가 권한 불요(공용 PLAN_TABLE + DBMS_XPLAN는 PUBLIC 실행 가능).
-- 주의: user_tables 기반 table-stats는 이 계정 자신의 스키마만 보이므로 빈 결과가 정상이다.
CREATE USER dbtower_monitor IDENTIFIED BY dbtower1234;
GRANT CREATE SESSION TO dbtower_monitor;
GRANT SELECT_CATALOG_ROLE TO dbtower_monitor;
GRANT READ ON sample.users TO dbtower_monitor;
