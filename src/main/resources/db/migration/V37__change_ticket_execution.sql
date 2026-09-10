-- 변경 티켓 실행 (워크벤치 3단계) — 승인된 리뷰 요청만 변경 계정으로 실행하고, 행 사본·전후 측정·되돌리기를 기록한다.
--
-- 왜: V28의 리뷰 게이트는 판정·승인까지만 했고 실행은 사람이 DB 도구로 따로 했다. 그러면 승인된 SQL과 실제 실행된 SQL이
-- 같다는 보장도, 되돌릴 사본도, 전후 비교도 남지 않는다. 실행을 게이트 뒤로 끌어와 셋을 같은 기록에 묶는다.
--
-- 상태 전이(review_request.status): APPROVED -> EXECUTING -> EXECUTED -> EXECUTING -> ROLLED_BACK.
-- EXECUTING은 조건부 UPDATE로만 얻는 실행권이다(동시에 두 사람이 실행을 눌러도 하나만 대상 DB에 닿는다).
ALTER TABLE review_request ADD COLUMN verify_sql TEXT;               -- 변경 전후로 실행계획·응답시간을 잴 검증 조회(선택)
ALTER TABLE review_request ADD COLUMN executed_by VARCHAR(255);
ALTER TABLE review_request ADD COLUMN executed_at TIMESTAMP;
ALTER TABLE review_request ADD COLUMN rolled_back_by VARCHAR(255);
ALTER TABLE review_request ADD COLUMN rolled_back_at TIMESTAMP;

CREATE TABLE workbench_change_execution (
    id                 BIGSERIAL    PRIMARY KEY,
    review_id          BIGINT       NOT NULL,
    instance_id        BIGINT       NOT NULL,
    action             VARCHAR(16)  NOT NULL,           -- DRY_RUN / EXECUTE / REVERT_DRY_RUN / REVERT
    kind               VARCHAR(16)  NOT NULL,           -- UPDATE / DELETE / INSERT / DDL / UNCAPTURED
    outcome            VARCHAR(16)  NOT NULL,           -- RUNNING / COMMITTED / ROLLED_BACK / FAILED / CONFLICT / UNCERTAIN
    table_name         VARCHAR(255),
    statement_sha256   VARCHAR(64)  NOT NULL,           -- 승인된 원문의 해시 — 실행한 문장이 승인본과 같다는 증거
    affected_rows      BIGINT,
    rollback_available BOOLEAN      NOT NULL DEFAULT FALSE,
    rollback_note      TEXT,                            -- 되돌리기 경로가 없으면 그 이유
    images             TEXT,                            -- 행 사본 JSON. 암호화 키가 있으면 AES-GCM 암호문
    images_encrypted   BOOLEAN      NOT NULL DEFAULT FALSE,
    images_expire_at   TIMESTAMP,                       -- 보존 기한 — 지나면 사본을 지우고 되돌리기를 닫는다
    schema_diff        TEXT,                            -- DDL 전후 구조 diff JSON(값이 아니라 구조라 평문)
    probe              TEXT,                            -- 검증 조회의 전후 실행계획·응답시간 JSON
    detail             TEXT,                            -- 실패 사유·충돌 목록
    principal          VARCHAR(255) NOT NULL,
    started_at         TIMESTAMP    NOT NULL,
    finished_at        TIMESTAMP,
    CONSTRAINT fk_change_execution_review FOREIGN KEY (review_id)
        REFERENCES review_request (id) ON DELETE CASCADE
);
CREATE INDEX idx_change_execution_review ON workbench_change_execution (review_id, started_at DESC);
CREATE INDEX idx_change_execution_expiry ON workbench_change_execution (images_expire_at) WHERE images IS NOT NULL;
