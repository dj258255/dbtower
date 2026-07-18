-- 대기 이벤트 주기 영속 (Phase 5 forward, D1) — lakehouse 장기 추세 분석의 공급원.
--
-- 왜: waitEvents()는 B1부터 5기종을 조회하지만 화면·MCP로 "지금"을 보여줄 뿐 영속이
-- 없었다 — "지난달 그 장애 때 뭘 기다렸나", "이 인스턴스는 매월 말 잠금 대기가 급증한다"
-- 같은 질문은 이력이 있어야 답한다. query_snapshot과 같은 운명: 여기 7일만 살고,
-- lakehouse가 D+1로 내려 장기 보관한다(레지스트리 편입 — lakehouse extract/tables.py).
--
-- 형태: 조회 모델(WaitEvent record)의 실형 그대로 — event/category에 count·total_ms
-- 두 측정값. 기종별 의미 차이(누적 vs 현재 스냅샷, B1의 정직 표기)는 소비자 계약
-- (lakehouse CONTRACT §1-1)에 기록한다.
CREATE TABLE wait_event_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    instance_id BIGINT           NOT NULL,
    captured_at TIMESTAMP        NOT NULL,
    event_name  VARCHAR(255)     NOT NULL,
    category    VARCHAR(64),
    wait_count  BIGINT           NOT NULL,
    total_ms    DOUBLE PRECISION NOT NULL,
    CONSTRAINT fk_wait_event_snapshot_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
);

-- 조회·추출 패턴: 인스턴스별 시간창(lakehouse offload 인덱스 선두 원칙과 동일).
CREATE INDEX idx_wait_event_snapshot_instance_time
    ON wait_event_snapshot (instance_id, captured_at);
