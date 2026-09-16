-- 경보 쿨다운을 프로세스 밖에 둔다 (VERIFICATION 170절 4번)
--
-- 감지기의 쿨다운은 노드별 인메모리 맵이었다. 락 보유 노드가 바뀌거나 재기동하면 이미 알린 신호를 쿨다운 창 안에서
-- 다시 알렸고, 예전에는 그것이 "중복 알림 1회"라 받아들였다(RegressionDetector 주석의 접근 (a)).
-- 경보가 AI 운영 작업을 만들게 된 뒤로는 그 1회가 대상 수만큼의 모델 호출이 되어 전제가 바뀌었다 —
-- 170절에서 재기동 직후 같은 회귀 경보가 다시 나고 작업까지 다시 만들어졌다.
--
-- 키는 "감지기:인스턴스id:신호"다. 값은 전송이 성공한 시각뿐이고, 판정은 여전히 감지기가 한다.
-- 시각은 alert_history.occurred_at과 같은 TIMESTAMP(시간대 없음) — 감지기가 LocalDateTime.now()를 쓰고
-- JVM 기본 시간대가 UTC로 고정돼 있다(DbtowerApplication C-6).
CREATE TABLE alert_cooldown (
    cooldown_key VARCHAR(300) PRIMARY KEY,
    alerted_at   TIMESTAMP    NOT NULL
);

CREATE INDEX alert_cooldown_alerted_at_idx ON alert_cooldown (alerted_at);
