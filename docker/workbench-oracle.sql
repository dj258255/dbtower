-- 워크벤치 콘솔 계정 + 데모 데이터 — Oracle (근거: docs/least-privilege.md "워크벤치 콘솔 계정")
-- 최초 1회 수동 실행(재실행하면 이미 있는 객체에서 ORA-00955/01920이 난다):
--   docker exec -i dbtower-oracle sqlplus -s / as sysdba < docker/workbench-oracle.sql
-- 한글 리터럴은 docker exec 표준입력의 NLS 인코딩에 따라 깨질 수 있어 데모 값은 영문으로 둔다.
ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE TABLE sample.customers (
    id     NUMBER PRIMARY KEY,
    name   VARCHAR2(50)  NOT NULL,
    email  VARCHAR2(100) NOT NULL,
    phone  VARCHAR2(20)  NOT NULL,
    grade  VARCHAR2(10)  NOT NULL
);
INSERT INTO sample.customers VALUES (1, 'Hong Gildong', 'hong@example.com', '01012345678', 'VIP');
INSERT INTO sample.customers VALUES (2, 'Kim Chulsoo', 'kim@example.com', '01098765432', 'GOLD');
INSERT INTO sample.customers VALUES (3, 'Lee Younghee', 'lee@example.com', '01055554444', 'SILVER');
COMMIT;

-- 조회 계정: 세션 + 대상 테이블 READ(SELECT와 달리 SELECT ... FOR UPDATE 락을 못 건다)
CREATE USER dbtower_reader IDENTIFIED BY dbtower1234;
GRANT CREATE SESSION TO dbtower_reader;
GRANT READ ON sample.users TO dbtower_reader;
GRANT READ ON sample.customers TO dbtower_reader;

-- 변경 계정(3단계, 승인된 티켓만 실행): DML과 데모 테이블 한 개의 열·외래키 추가 DDL.
-- 외래키는 자식 테이블 ALTER에 더해 가리키는 테이블의 REFERENCES가 있어야 만들어진다(VERIFICATION 151·157절).
-- CREATE TABLE·DROP TABLE·권한 변경은 주지 않는다 — 승인된 티켓이라도 계정 권한이 마지막 겹이다.
CREATE USER dbtower_writer IDENTIFIED BY dbtower1234;
GRANT CREATE SESSION TO dbtower_writer;
GRANT SELECT, INSERT, UPDATE, DELETE ON sample.customers TO dbtower_writer;
GRANT SELECT, INSERT, UPDATE, DELETE, ALTER ON sample.change_it TO dbtower_writer;
GRANT REFERENCES ON sample.customers TO dbtower_writer;

-- 모니터 계정의 스키마 트리·EXPLAIN용
GRANT READ ON sample.customers TO dbtower_monitor;
GRANT READ ON sample.change_it TO dbtower_monitor;
EXIT;
