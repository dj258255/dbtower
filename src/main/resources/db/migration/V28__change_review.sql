-- 스키마 변경 리뷰 게이트 (운영 병목 아크 B2) — 개발자의 DDL/대량 DML 배포 전 리뷰 요청을
-- 자동 판정·승인·기록까지. 실행은 여전히 범위 밖(gh-ost 기존 경로 또는 사람) — 여기선 판정과
-- 승인 워크플로만 관장한다(관제탑은 대상 DB에 임의 DDL을 실행하지 않는다).
--
-- 왜: 현업 DBA 병목 1순위가 "변경 요청 리뷰 대기"다. 규칙 엔진이 락 위험·WHERE 없는 대량
-- 변경·DROP을 자동 지적하고, AI가 1차 소견을 붙이고, ADMIN이 승인/반려하며, 전 과정이 감사
-- 로그(POST/PUT은 AuditInterceptor가 자동 기록)에 남는다.
--
-- findings·ai_opinion은 판정 시점 스냅샷(규칙이 늘어도 과거 판정과 비교 가능하게 rules_version 보존).
CREATE TABLE review_request (
    id               BIGSERIAL   PRIMARY KEY,
    instance_id      BIGINT      NOT NULL,
    target_sql       TEXT        NOT NULL,           -- 리뷰 대상 SQL(원문 — 콘솔 ADMIN 전용, 카드엔 마스킹본)
    reason           TEXT,                            -- 요청 사유
    requester        VARCHAR(255) NOT NULL,           -- 제출자(로그인 principal)
    status           VARCHAR(16) NOT NULL,            -- PENDING / APPROVED / REJECTED
    findings         TEXT,                            -- 규칙 지적(줄바꿈 결합) — 판정 시점 스냅샷
    ai_opinion       TEXT,                            -- AI 1차 소견(미활성/근거없음이면 null)
    rules_version    INT         NOT NULL,            -- 판정에 쓰인 규칙 버전
    parse_limited    BOOLEAN     NOT NULL DEFAULT FALSE, -- 정규식 파싱 한계로 판정이 불완전할 수 있음(정직)
    submitted_at     TIMESTAMP   NOT NULL,
    decided_by       VARCHAR(255),                    -- 승인/반려한 ADMIN(미결정이면 null)
    decided_at       TIMESTAMP,
    decision_comment TEXT,
    CONSTRAINT fk_review_request_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
);
CREATE INDEX idx_review_request_instance ON review_request (instance_id, submitted_at DESC);
CREATE INDEX idx_review_request_status ON review_request (status);
