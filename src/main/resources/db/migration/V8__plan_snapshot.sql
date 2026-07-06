-- 실행계획 스냅샷 — 플랜 변경(plan flip) 감지용. 회귀가 감지된 쿼리만 계획을 떠서
-- 정규화 형태(shape)의 해시를 남기고, 다음 회귀 때 달라졌으면 "플랜 변경"으로 알린다.
-- 모든 쿼리를 매번 뜨지 않는 이유: 진단이 부하 유발자가 되면 안 된다(A9 원칙).
CREATE TABLE plan_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    instance_id BIGINT       NOT NULL,
    query_id    VARCHAR(255) NOT NULL,
    plan_hash   VARCHAR(64)  NOT NULL,
    plan_shape  VARCHAR(2000),
    captured_at TIMESTAMP    NOT NULL
);

-- 조회 패턴: (인스턴스, 쿼리)의 최신 스냅샷 — 등치 컬럼 선두 복합 인덱스(개선 아크 3과 같은 원칙)
CREATE INDEX idx_plan_snapshot_lookup ON plan_snapshot (instance_id, query_id, captured_at DESC);
