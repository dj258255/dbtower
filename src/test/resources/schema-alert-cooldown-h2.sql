-- 테스트 H2에는 Flyway가 돌지 않아 JDBC 전용 테이블이 생기지 않는다(엔티티가 없어 create-drop도 안 만든다).
-- 감지기는 스케줄로 컨텍스트 테스트 중에도 돌고, 쿨다운 판정마다 이 테이블을 읽는다 — 없으면 감지가 조용히 예외로 끝난다.
-- 운영 스키마는 db/migration/V45__alert_cooldown.sql이 만든다. 이 파일은 그 DDL을 그대로 옮긴 것이다.
CREATE TABLE IF NOT EXISTS alert_cooldown (
    cooldown_key VARCHAR(300) PRIMARY KEY,
    alerted_at   TIMESTAMP    NOT NULL
);
