-- 설정 드리프트 이력 (운영 병목 아크 B1) — "언제부터 무엇이 바뀌었나"를 시간축으로.
--
-- 왜: 파라미터 diff(B6)는 공간축("A와 B가 다른가")뿐이었다. "누가 work_mem 줄였어?",
-- "이 인스턴스 언제부터 max_connections가 달라졌지?"는 시간축이 있어야 답한다. 원천은
-- 기존 parameters()(5기종 구현 완료) 재사용 — 신규 Operator 코드 0줄.
--
-- 형태(폭증 방지): 전체를 매 주기 저장하지 않는다. config_current_param이 인스턴스별
-- '현재 전량'을 거울처럼 들고(변경분만 upsert/delete), 바뀐 항목만 config_param_change에
-- append한다. config_snapshot은 매 수집 1행(해시 = 무변경 증거) — 무변경 주기엔 이 한 줄만 쌓인다.
--
-- 정직: "누가"는 대상 DB가 주지 않는 정보라 저장하지 않는다(콘솔에서 대상 DB 감사 로그의
-- 몫임을 안내). 첫 수집은 '변경'이 아니라 '기준선'(baseline=true)이라 경보를 내지 않는다.

-- 매 수집의 흔적 — 무변경도 1행(해시로 "그 시각 설정은 이랬다"를 증명). 변경 수·기준선 여부.
CREATE TABLE config_snapshot (
    id           BIGSERIAL   PRIMARY KEY,
    instance_id  BIGINT      NOT NULL,
    captured_at  TIMESTAMP   NOT NULL,
    param_hash   VARCHAR(64) NOT NULL,           -- 전체 파라미터 집합의 SHA-256(무변경 증거)
    change_count INT         NOT NULL,           -- 직전 대비 변경 수(0 = 무변경)
    baseline     BOOLEAN     NOT NULL DEFAULT FALSE, -- 첫 수집(기준선) — 경보 억제 근거
    CONSTRAINT fk_config_snapshot_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
);
CREATE INDEX idx_config_snapshot_instance_time ON config_snapshot (instance_id, captured_at);

-- 변경 이벤트 로그(append-only) — 바뀐 파라미터만. 콘솔 타임라인·플랜 플립 대조의 원천.
CREATE TABLE config_param_change (
    id           BIGSERIAL   PRIMARY KEY,
    snapshot_id  BIGINT      NOT NULL,
    instance_id  BIGINT      NOT NULL,
    captured_at  TIMESTAMP   NOT NULL,
    param_name   VARCHAR(255) NOT NULL,
    old_value    TEXT,
    new_value    TEXT,
    change_kind  VARCHAR(16) NOT NULL,           -- CHANGED / ADDED / REMOVED
    CONSTRAINT fk_config_param_change_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES config_snapshot (id) ON DELETE CASCADE
);
CREATE INDEX idx_config_param_change_instance_time ON config_param_change (instance_id, captured_at);

-- 현재 전량 거울 — 다음 수집의 diff 기준. 인스턴스당 파라미터 수만큼, 변경분만 upsert/delete.
-- 값은 parameters()가 준 표기 그대로(민감값은 이미 마스킹됨 — 좌우 동일 토큰이라 오탐 없음).
CREATE TABLE config_current_param (
    instance_id BIGINT       NOT NULL,
    param_name  VARCHAR(255) NOT NULL,
    param_value TEXT,
    PRIMARY KEY (instance_id, param_name),
    CONSTRAINT fk_config_current_param_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
);
