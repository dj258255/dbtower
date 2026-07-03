-- DBTower 최소 권한 모니터링 계정 — SQL Server 2022 (실측 근거: docs/least-privilege.md)
--
-- 주의: docker-compose.yml은 이 파일을 마운트하지 않는다 (compose 무수정 정책 + mssql 이미지는
-- docker-entrypoint-initdb.d 방식 자체가 없다). 수동 실행:
--   docker exec -i dbtower-mssql /opt/mssql-tools18/bin/sqlcmd \
--     -S localhost -U sa -P 'Dbtower1234!' -C -i /dev/stdin < docker/mssql-init.sql

-- 'dbtower1234'는 기본 암호 정책(4종 중 3종 문자 조합) 미달로 거부되므로 CHECK_POLICY = OFF.
-- (실측 에러: Msg 33064 "Password validation failed ... not complex enough")
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'dbtower_monitor')
    CREATE LOGIN dbtower_monitor WITH PASSWORD = 'dbtower1234', CHECK_POLICY = OFF;
GO

-- 2022의 세분화 권한 하나로 전 기능 통과:
--   DMV 조회(query-stats/slow-queries/explain: VIEW SERVER PERFORMANCE STATE)
--   + 하위 포함되는 VIEW DATABASE PERFORMANCE STATE(table-stats/replication)
-- 포괄 권한 VIEW SERVER STATE까지는 불요. master는 guest 사용자로 접속되어 별도 DB 사용자 생성 불요.
GRANT VIEW SERVER PERFORMANCE STATE TO dbtower_monitor;
GO
