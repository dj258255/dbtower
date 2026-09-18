-- 워크벤치 콘솔 계정 + 데모 데이터 — SQL Server 계열 (근거: docs/operate/least-privilege.md "워크벤치 콘솔 계정", VERIFICATION 131절)
-- 검증 환경은 arm64 Azure SQL Edge(docker-compose.arm64.yml)다. 이미지에 sqlcmd가 없어 JDBC로 적용한다. 여러 번 실행해도 같다:
--   DB_PASSWORD='Dbtower1234!' java -cp <mssql-jdbc.jar> scripts/ApplySql.java \
--     "jdbc:sqlserver://127.0.0.1:11433;encrypt=false" sa docker/workbench-mssql.sql
-- 줄 단독 GO가 배치 구분자다.

IF DB_ID('sample') IS NULL CREATE DATABASE sample;
GO

-- 로그인은 서버 수준(master). 데모 암호는 기본 암호 정책 미달이라 CHECK_POLICY = OFF(least-privilege.md SQL Server 절과 같은 이유).
USE master;
GO
IF SUSER_ID('dbtower_monitor') IS NULL CREATE LOGIN dbtower_monitor WITH PASSWORD = 'dbtower1234', CHECK_POLICY = OFF;
IF SUSER_ID('dbtower_reader') IS NULL CREATE LOGIN dbtower_reader WITH PASSWORD = 'dbtower1234', CHECK_POLICY = OFF;
IF SUSER_ID('dbtower_writer') IS NULL CREATE LOGIN dbtower_writer WITH PASSWORD = 'dbtower1234', CHECK_POLICY = OFF;
-- SQL Server 2022의 VIEW SERVER PERFORMANCE STATE는 SQL Edge(15.0 엔진)에 없어 이전 형태인 VIEW SERVER STATE를 준다
GRANT VIEW SERVER STATE TO dbtower_monitor;
GO

USE sample;
GO
IF OBJECT_ID('dbo.customers') IS NULL
    CREATE TABLE dbo.customers (
        id         INT           PRIMARY KEY,
        name       NVARCHAR(50)  NOT NULL,
        email      NVARCHAR(100) NOT NULL,
        phone      NVARCHAR(20)  NOT NULL,
        grade      NVARCHAR(10)  NOT NULL,
        created_at DATETIME2     NOT NULL DEFAULT SYSDATETIME()
    );
GO
IF NOT EXISTS (SELECT 1 FROM dbo.customers)
    INSERT INTO dbo.customers (id, name, email, phone, grade) VALUES
        (1, N'홍길동', 'hong@example.com', '01012345678', 'VIP'),
        (2, N'김철수', 'kim@example.com', '01098765432', 'GOLD'),
        (3, N'이영희', 'lee@example.com', '01055554444', 'SILVER');
GO
IF OBJECT_ID('dbo.orders') IS NULL
    CREATE TABLE dbo.orders (
        id          INT          IDENTITY(1, 1) PRIMARY KEY,
        customer_id INT          NOT NULL,
        amount      INT          NOT NULL,
        status      NVARCHAR(20) NOT NULL,
        ordered_at  DATETIME2    NOT NULL DEFAULT SYSDATETIME()
    );
GO
IF NOT EXISTS (SELECT 1 FROM dbo.orders)
BEGIN;
    WITH seq AS (SELECT TOP (2000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
                 FROM sys.all_objects a CROSS JOIN sys.all_objects b)
    INSERT INTO dbo.orders (customer_id, amount, status)
    SELECT 1 + n % 3, (n * 37) % 100000, CHOOSE(1 + n % 3, 'PAID', 'FAIL', 'REFUND') FROM seq;
END;
GO

IF USER_ID('dbtower_monitor') IS NULL CREATE USER dbtower_monitor FOR LOGIN dbtower_monitor;
IF USER_ID('dbtower_reader') IS NULL CREATE USER dbtower_reader FOR LOGIN dbtower_reader;
IF USER_ID('dbtower_writer') IS NULL CREATE USER dbtower_writer FOR LOGIN dbtower_writer;
GO

-- 모니터: 스키마 트리·테이블 상세가 카탈로그에서 대상 테이블을 보려면 정의 조회가 필요하다
GRANT VIEW DEFINITION ON SCHEMA::dbo TO dbtower_monitor;
-- 조회 계정: dbo 스키마 SELECT만. SQL Server 드라이버는 읽기 전용 힌트를 무시하므로 이 권한이 조회 경계의 전부다
GRANT SELECT ON SCHEMA::dbo TO dbtower_reader;
-- 변경 계정: DML + 검증 조회 실행계획(SHOWPLAN). DDL은 테이블 단위 ALTER만 — 인덱스·열 추가는 되고
-- DROP TABLE은 스키마 ALTER나 테이블 CONTROL이 필요해 거부된다(승인된 티켓이라도 계정 권한이 마지막 겹)
GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA::dbo TO dbtower_writer;
GRANT SHOWPLAN TO dbtower_writer;
GRANT ALTER ON dbo.customers TO dbtower_writer;
GRANT ALTER ON dbo.orders TO dbtower_writer;
-- 외래키를 거는 ALTER는 가리키는 테이블의 REFERENCES도 요구한다(VERIFICATION 151절: 승인된 외래키 티켓이 이 권한이 없어 실행에서 거부됐다)
GRANT REFERENCES ON dbo.customers TO dbtower_writer;
GRANT REFERENCES ON dbo.orders TO dbtower_writer;
GO
