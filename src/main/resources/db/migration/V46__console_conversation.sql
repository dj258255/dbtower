-- 관제 오른쪽 AI 칸의 대화를 서버에 남긴다 (173절 다음 작업).
--
-- 왜 필요한가. 문제가 둘이었다.
--   (1) 대화가 브라우저 sessionStorage에만 있어 탭을 닫으면 사라졌고, 대화 목록이라는 것이 없었다.
--   (2) 앞선 대화 맥락을 요청 본문 history로 받았다 — 브라우저가 보낸 글을 그대로 믿으므로
--       위조된 맥락이 진단 프롬프트에 실릴 수 있었다.
-- 워크벤치 AI 대화(V36)와 같이 대화의 단일 권위를 서버에 둔다. 맥락도 서버 기록에서만 만든다.
--
-- instance_id에 FK를 걸지 않는다: V36의 대화와 같은 이유로, 인스턴스가 지워져도 "AI가 무엇을 물었나"는 남는다.
CREATE TABLE console_conversation (
    id          BIGSERIAL PRIMARY KEY,
    principal   VARCHAR(100) NOT NULL,   -- 대화를 만든 사람. 다른 사용자에게는 미등록과 같은 404다
    instance_id BIGINT       NOT NULL,
    title       VARCHAR(120) NOT NULL,   -- 첫 턴이 저장될 때 질문 앞 40자로 바뀐다(그전에는 "새 대화")
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL    -- 마지막 턴 시각. 목록은 이 컬럼 내림차순이다
);
CREATE INDEX idx_console_conversation_owner ON console_conversation (principal, instance_id, updated_at DESC);

-- 진단 한 턴. tool_calls에는 ToolCallTrace 목록 JSON을 넣되 결과 본문(resultSnippet)은 비워서 저장한다 —
-- 대상 DB에서 온 값을 메타 DB에 쌓지 않기 위해서다. 도구 이름·마스킹된 인자·부른 이유·거부 여부만 남긴다.
CREATE TABLE console_conversation_turn (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES console_conversation (id) ON DELETE CASCADE,
    question        TEXT        NOT NULL,
    answer          TEXT,
    root_cause      TEXT,
    confidence      VARCHAR(16),
    backend         VARCHAR(40),
    tool_calls      TEXT,
    took_ms         BIGINT      NOT NULL,   -- 서버가 잰 진단 소요 시간
    created_at      TIMESTAMP   NOT NULL
);
CREATE INDEX idx_console_conversation_turn ON console_conversation_turn (conversation_id, created_at);
