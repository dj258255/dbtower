-- 워크벤치 콘솔 계정 + 데모 데이터 — PostgreSQL (근거: docs/operate/least-privilege.md "워크벤치 콘솔 계정")
-- 수동 실행: docker exec -i dbtower-postgres psql -U postgres -d sample < docker/workbench-postgres.sql
-- 여러 번 실행해도 결과가 같다.

CREATE TABLE IF NOT EXISTS customers (
    id          INT PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    email       VARCHAR(100) NOT NULL,
    phone       VARCHAR(20)  NOT NULL,
    grade       VARCHAR(10)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
INSERT INTO customers (id, name, email, phone, grade) VALUES
    (1, '홍길동', 'hong@example.com', '01012345678', 'VIP'),
    (2, '김철수', 'kim@example.com', '01098765432', 'GOLD'),
    (3, '이영희', 'lee@example.com', '01055554444', 'SILVER')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS orders (
    id           SERIAL PRIMARY KEY,
    customer_id  INT         NOT NULL,
    amount       INT         NOT NULL,
    status       VARCHAR(20) NOT NULL,
    ordered_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders (customer_id);
INSERT INTO orders (customer_id, amount, status)
SELECT 1 + n % 3, (n * 37) % 100000, (ARRAY['PAID', 'FAIL', 'REFUND'])[1 + n % 3]
FROM generate_series(1, 2000) AS n
WHERE NOT EXISTS (SELECT 1 FROM orders);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dbtower_reader') THEN
        CREATE ROLE dbtower_reader LOGIN PASSWORD 'dbtower1234';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dbtower_writer') THEN
        CREATE ROLE dbtower_writer LOGIN PASSWORD 'dbtower1234';
    END IF;
END $$;

-- 조회 계정: 스키마 사용 + 테이블 SELECT만
GRANT CONNECT ON DATABASE sample TO dbtower_reader;
GRANT USAGE ON SCHEMA public TO dbtower_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO dbtower_reader;

-- 변경 계정(승인된 티켓만 실행): DML과 SERIAL 시퀀스 사용
GRANT CONNECT ON DATABASE sample TO dbtower_writer;
GRANT USAGE ON SCHEMA public TO dbtower_writer;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO dbtower_writer;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO dbtower_writer;

-- PostgreSQL DDL(인덱스·열 추가)은 테이블 소유자만 한다. 계정에 소유권을 직접 주지 않고 로그인 불가 소유 역할을 두어
-- 멤버십 한 줄로 부여·회수한다(REVOKE sample_owner FROM dbtower_writer). 소유권 이전은 기존 GRANT를 유지한다.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sample_owner') THEN
        CREATE ROLE sample_owner NOLOGIN;
    END IF;
END $$;
ALTER TABLE customers OWNER TO sample_owner;
ALTER TABLE orders OWNER TO sample_owner;
-- 테이블 소유만으로는 CREATE INDEX가 안 된다: PostgreSQL 15부터 public 스키마의 기본 CREATE가 없어졌고 인덱스 생성은
-- 스키마 CREATE 권한을 따로 본다(라이브 검증에서 "permission denied for schema public", VERIFICATION 130절).
GRANT CREATE ON SCHEMA public TO sample_owner;
GRANT sample_owner TO dbtower_writer;

-- 모니터 계정의 스키마 트리(describeSchema)와 EXPLAIN은 테이블 SELECT를 요구한다(least-privilege.md PostgreSQL 절)
GRANT SELECT ON customers, orders TO dbtower_monitor;
