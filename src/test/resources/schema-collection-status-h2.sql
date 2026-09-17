-- 테스트 H2에는 Flyway가 돌지 않는다. 운영 스키마는 db/migration/V47__collection_status.sql이 만들고, 이 파일은 그 DDL을 옮긴 것이다.
CREATE TABLE IF NOT EXISTS collection_status (
    instance_id          BIGINT      PRIMARY KEY,
    last_success_at      TIMESTAMP,
    last_failure_at      TIMESTAMP,
    consecutive_failures INT         NOT NULL DEFAULT 0,
    failure_stage        VARCHAR(20)
);
