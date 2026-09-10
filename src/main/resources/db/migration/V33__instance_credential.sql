-- 워크벤치 콘솔 계정 (거버넌스 SQL 워크벤치 1단계) — 모니터 계정과 분리된 조회(READ)·변경(WRITE) 계정.
-- 모니터 계정은 수집기가 필요한 권한만 갖도록 최소화돼 있다(least-privilege.md). 기종에 따라 업무 테이블을 아예
-- 못 읽고(SQL Server), 읽을 수 있더라도(EXPLAIN용 SELECT) 그 계정을 사람의 자유 조회에 쓰면 관제 계정이 곧 데이터
-- 접근 계정이 된다. 권한과 기록을 사람 경로로 따로 떼어 둔다.
CREATE TABLE instance_credential (
    id           BIGSERIAL PRIMARY KEY,
    instance_id  BIGINT       NOT NULL REFERENCES database_instance (id) ON DELETE CASCADE,
    purpose      VARCHAR(16)  NOT NULL CHECK (purpose IN ('READ', 'WRITE')),
    username     VARCHAR(255) NOT NULL,
    password     VARCHAR(512) NOT NULL,        -- EncryptedStringConverter(AES-256-GCM)로 암호화된 값
    updated_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uk_instance_credential_purpose UNIQUE (instance_id, purpose)
);
