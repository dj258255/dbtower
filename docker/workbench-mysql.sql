-- 워크벤치 콘솔 계정 + 데모 데이터 — MySQL (근거: docs/least-privilege.md "워크벤치 콘솔 계정")
-- mysql-init.sql과 같은 정책(compose 무수정)이라 수동 실행한다:
--   docker exec -i -e MYSQL_PWD=dbtower1234 dbtower-mysql mysql -uroot < docker/workbench-mysql.sql
-- 여러 번 실행해도 결과가 같다.

SET NAMES utf8mb4;

-- 마스킹 데모용: 이메일·전화번호 같은 개인정보 컬럼이 있는 테이블
CREATE TABLE IF NOT EXISTS sample.customers (
    id          INT PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    email       VARCHAR(100) NOT NULL,
    phone       VARCHAR(20)  NOT NULL,
    grade       VARCHAR(10)  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT IGNORE INTO sample.customers (id, name, email, phone, grade) VALUES
    (1, '홍길동', 'hong@example.com', '01012345678', 'VIP'),
    (2, '김철수', 'kim@example.com', '01098765432', 'GOLD'),
    (3, '이영희', 'lee@example.com', '01055554444', 'SILVER');

CREATE TABLE IF NOT EXISTS sample.orders (
    id           INT PRIMARY KEY AUTO_INCREMENT,
    customer_id  INT         NOT NULL,
    amount       INT         NOT NULL,
    status       VARCHAR(20) NOT NULL,
    ordered_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_orders_customer (customer_id)
);
SET SESSION cte_max_recursion_depth = 5000;
INSERT INTO sample.orders (customer_id, amount, status)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2000)
SELECT 1 + MOD(n, 3), MOD(n * 37, 100000), ELT(1 + MOD(n, 3), 'PAID', 'FAIL', 'REFUND')
FROM seq
WHERE NOT EXISTS (SELECT 1 FROM sample.orders);

-- 조회 계정: 대상 스키마 SELECT만. 모니터 계정과 분리한다(같은 계정이면 플랫폼이 저장을 거부한다).
CREATE USER IF NOT EXISTS 'dbtower_reader'@'%' IDENTIFIED BY 'dbtower1234';
GRANT SELECT ON sample.* TO 'dbtower_reader'@'%';

-- 변경 계정(승인된 티켓만 실행): DML과 인덱스·열·외래키 추가 DDL(ALTER·INDEX·REFERENCES). DROP·CREATE TABLE·권한 변경은 주지 않는다 —
-- 승인된 티켓이라도 계정 권한 밖의 문장은 대상 DB가 거부한다(마지막 방어선). 대형 테이블 DDL은 gh-ost 경로로 보낸다.
-- 외래키는 자식 테이블 ALTER에 더해 가리키는 테이블의 REFERENCES가 있어야 만들어진다(VERIFICATION 151절: 승인된 외래키 티켓이 이 권한이 없어 실행에서 거부됐다).
CREATE USER IF NOT EXISTS 'dbtower_writer'@'%' IDENTIFIED BY 'dbtower1234';
GRANT SELECT, INSERT, UPDATE, DELETE, ALTER, INDEX, REFERENCES ON sample.* TO 'dbtower_writer'@'%';
