-- 로그인 브루트포스 잠금 카운터를 메타 DB로 (Phase 3) — 인메모리(노드별 독립)는 LB 뒤 N노드에서
-- 실패 허용치가 사실상 N배가 된다. 공유 세션(V15)과 같은 논리로 메타 DB를 공유 스토어로 쓴다.
CREATE TABLE login_attempt (
    username     VARCHAR(100) PRIMARY KEY,
    failures     INT          NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL
);
