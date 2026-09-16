-- 작업 접수와 외부 큐 전달 사이의 유실을 막는 Outbox (VERIFICATION 169절)
--
-- 플랫폼은 Redis를 모른다. 릴레이(AI 실행면의 프로세스)가 이 행을 리스로 선점해 Redis Streams에 넣고 발행 완료를 알린다.
-- 선점은 조건부 UPDATE 한 문장이다 — PENDING이거나 리스가 만료된 CLAIMED만 가져간다. 릴레이가 발행 직후 죽으면
-- 리스 만료 뒤 다시 발행되어 같은 작업이 두 번 배달될 수 있는데, 실행기의 작업 선점(RECEIVED -> AUTHORIZED)이 흡수한다.
-- payload에는 요청 문장을 넣지 않는다 — 큐에는 식별자만 흐르고 내용은 권한 검사를 거친 API로만 읽는다.
CREATE TABLE ai_operation_outbox (
    event_id      VARCHAR(36) PRIMARY KEY,
    job_id        VARCHAR(36) NOT NULL REFERENCES ai_operation_job(job_id) ON DELETE CASCADE,
    event_type    VARCHAR(60) NOT NULL,
    payload       TEXT        NOT NULL,
    status        VARCHAR(20) NOT NULL,
    attempts      INTEGER     NOT NULL,
    claim_token   VARCHAR(36),
    claimed_until TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    published_at  TIMESTAMPTZ
);

CREATE INDEX ai_operation_outbox_status_created_idx ON ai_operation_outbox (status, created_at);
