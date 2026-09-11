-- 워크벤치 실행 기록 — 누가 어느 인스턴스에서 어떤 문장을 실행(또는 거부)했고 몇 행을 봤는지.
-- audit_event는 HTTP 요청 단위라 문장·행 수·마스킹 여부·내보내기 사유를 담지 못한다.
-- instance_id에 FK를 걸지 않는다: 인스턴스를 지워도 "그 대상에서 누가 무엇을 봤는가"는 남아야 한다.
CREATE TABLE workbench_query_log (
    id              BIGSERIAL PRIMARY KEY,
    occurred_at     TIMESTAMP    NOT NULL,
    principal       VARCHAR(255) NOT NULL,
    instance_id     BIGINT       NOT NULL,
    action          VARCHAR(20)  NOT NULL,     -- QUERY / EXPORT
    tier            VARCHAR(20)  NOT NULL,     -- READ / NEEDS_APPROVAL / BLOCKED
    kind            VARCHAR(40)  NOT NULL,     -- 분류기가 붙인 문장 유형
    statement       TEXT         NOT NULL,     -- 리터럴 마스킹 후(QueryMasker)
    outcome         VARCHAR(20)  NOT NULL,     -- OK / REJECTED / ERROR
    row_count       INTEGER,
    truncated       BOOLEAN,
    masked_columns  VARCHAR(500),
    elapsed_ms      BIGINT,
    reason          VARCHAR(500),              -- 내보내기 사유
    error           VARCHAR(500)
);

CREATE INDEX idx_workbench_query_log_instance_time ON workbench_query_log (instance_id, occurred_at DESC);
CREATE INDEX idx_workbench_query_log_principal_time ON workbench_query_log (principal, occurred_at DESC);
