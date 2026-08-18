-- 알림 이력 (다관점 감사) — 감지 알림에 영속 기록이 없어 사후에 "그때 알림이 왔었나"를
-- 확인할 방법이 없었다. 인시던트 리포트도 이 한계를 스스로 인정하고 감지 알림을 타임라인에서 빼고 있었다.
--
-- status로 전송 결과를 남긴다: SENT(전달됨) / FAILED(전송 실패·레이트리밋 — 다음 폴에서 재시도된다).
-- 쿨다운이 전송 성공 후에만 확정되므로 FAILED 행은 "재시도 대기 중"이라는 뜻이다.
CREATE TABLE alert_history (
    id           BIGSERIAL PRIMARY KEY,
    instance_id  BIGINT,                       -- 함대 전체 리포트는 NULL
    kind         VARCHAR(40)  NOT NULL,        -- ops / regression / anomaly
    severity     VARCHAR(20)  NOT NULL,        -- RED / AMBER / PURPLE
    summary      TEXT         NOT NULL,        -- 알림 본문(폴백 텍스트)
    status       VARCHAR(20)  NOT NULL,        -- SENT / FAILED
    occurred_at  TIMESTAMP    NOT NULL
);

-- 조회는 항상 "이 인스턴스의 최근 알림" 또는 "이 구간의 알림"이다.
CREATE INDEX idx_alert_history_instance_time ON alert_history (instance_id, occurred_at DESC);
CREATE INDEX idx_alert_history_time ON alert_history (occurred_at DESC);
