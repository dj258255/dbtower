-- 워크벤치 워크시트와 AI 보조 (2단계) — TOI류 AI 화면 생성 도구의 "페이지마다 대화, 수정마다 버전" 구조를 SQL에 옮긴다.
--
-- 워크시트는 인스턴스 아래 사람이 여는 작업 단위(TOI의 페이지)다. 대화·SQL 버전을 서버에 두는 이유는 감사다:
-- "누가 어떤 AI 제안을 받아 무엇을 실행했나"가 workbench_query_log와 이어져야 하고, 브라우저 저장소에 두면 끊긴다.

CREATE TABLE workbench_worksheet (
    id           BIGSERIAL PRIMARY KEY,
    principal    VARCHAR(255) NOT NULL,
    instance_id  BIGINT       NOT NULL REFERENCES database_instance (id) ON DELETE CASCADE,
    title        VARCHAR(100) NOT NULL,
    current_sql  TEXT         NOT NULL DEFAULT '',   -- 편집기 자동 저장본(버전이 아니다)
    archived     BOOLEAN      NOT NULL DEFAULT FALSE, -- 지우지 않고 보관한다 — 대화·버전 기록을 남기기 위해
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);
CREATE INDEX idx_workbench_worksheet_owner ON workbench_worksheet (principal, instance_id, updated_at DESC);

-- 대화 한 턴. worksheet_id·instance_id에 FK를 걸지 않는다: 인스턴스가 지워져도 "AI가 무엇을 제안했나"는 남아야 한다.
CREATE TABLE workbench_chat_message (
    id              BIGSERIAL PRIMARY KEY,
    worksheet_id    BIGINT       NOT NULL,
    principal       VARCHAR(255) NOT NULL,
    instance_id     BIGINT       NOT NULL,
    role            VARCHAR(16)  NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content         TEXT         NOT NULL,
    proposed_sql    TEXT,
    tier            VARCHAR(20),               -- 제안 SQL의 분류
    kind            VARCHAR(40),
    unknown_tables  VARCHAR(500),              -- 스키마에 없는 테이블(환각 신호)
    values_shared   BOOLEAN      NOT NULL DEFAULT FALSE,  -- 이 요청에서 결과 값이 AI로 나갔는가
    created_at      TIMESTAMP    NOT NULL
);
CREATE INDEX idx_workbench_chat_worksheet ON workbench_chat_message (worksheet_id, created_at);

-- SQL 버전. 되돌리기도 새 버전으로 쌓는다(git revert처럼) — 과거를 덮어쓰면 실행 기록의 SQL과 이력이 어긋난다.
CREATE TABLE workbench_sql_version (
    id               BIGSERIAL PRIMARY KEY,
    worksheet_id     BIGINT      NOT NULL,
    version_no       INTEGER     NOT NULL,
    sql              TEXT        NOT NULL,
    source           VARCHAR(16) NOT NULL CHECK (source IN ('AI', 'RUN', 'RESTORE')),
    chat_message_id  BIGINT,                   -- AI 제안이면 그 답변
    restored_from    INTEGER,                  -- 되돌리기면 원본 버전 번호
    title            VARCHAR(200),             -- 체크포인트 카드 제목(AI가 붙인 작업 이름, 실행·되돌리기는 고정 문구)
    principal        VARCHAR(255) NOT NULL,    -- 이 버전을 만든 사람(AI 제안이면 요청한 사람)
    created_at       TIMESTAMP   NOT NULL,
    CONSTRAINT uk_workbench_sql_version UNIQUE (worksheet_id, version_no)
);

-- 인스턴스별 설정. 행이 없으면 기본값(결과 값을 AI에 보내지 않음) — fail-closed.
CREATE TABLE workbench_setting (
    instance_id              BIGINT PRIMARY KEY REFERENCES database_instance (id) ON DELETE CASCADE,
    allow_ai_result_values   BOOLEAN   NOT NULL DEFAULT FALSE,
    updated_by               VARCHAR(255),
    updated_at               TIMESTAMP NOT NULL
);
