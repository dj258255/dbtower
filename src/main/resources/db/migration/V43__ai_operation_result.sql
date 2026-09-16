-- AI 운영 작업의 사실·규칙 판정·소견·근거 (VERIFICATION 169절)
--
-- 행은 사실을 수집하는 순간 만들어진다(facts·rule_findings만 채움). 모델 소견은 분석이 끝난 뒤 같은 행에 붙는다.
-- 사실을 먼저 저장하는 이유: 결과 검증이 "실행기가 보낸 사실"이 아니라 "DBTower가 모은 사실"과 대조해야 한다.
-- 목록은 JSON 문자열로 둔다 — 항목을 행 단위로 질의할 일이 없고, 결과 틀이 바뀔 때마다 스키마를 늘리지 않는다.
CREATE TABLE ai_operation_result (
    job_id            VARCHAR(36) PRIMARY KEY REFERENCES ai_operation_job(job_id) ON DELETE CASCADE,
    facts             TEXT        NOT NULL,
    rule_findings     TEXT        NOT NULL,
    ai_opinion        TEXT,
    evidence          TEXT        NOT NULL,
    uncertainties     TEXT        NOT NULL,
    next_actions      TEXT        NOT NULL,
    reference_items   TEXT        NOT NULL,
    unverified_claims TEXT        NOT NULL,
    approval_required BOOLEAN     NOT NULL,
    backend           VARCHAR(20),
    prompt_version    VARCHAR(80),
    collected_at      TIMESTAMPTZ NOT NULL,
    completed_at      TIMESTAMPTZ
);
