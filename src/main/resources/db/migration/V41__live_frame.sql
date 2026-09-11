-- 실시간 세션 프레임 교환 (VERIFICATION 144절)
--
-- 앱 노드가 여럿이면 노드마다 허브가 대상을 따로 조회해 대상 조회가 노드 수만큼 늘었다(140절 한계).
-- 대상마다 한 노드만 조회권(ShedLock)을 쥐고 프레임을 여기 올리며, 다른 노드는 이 행을 읽어 자기 구독자에게 넘긴다.
--
-- UNLOGGED인 이유: 프레임은 몇 초짜리 일회성 값이다. 크래시 뒤 비어도 다음 틱(2초)에 다시 채워지고, WAL을 쓰지 않아
-- 틱마다 세션 목록 JSON을 덮어써도 복제·백업 부피를 만들지 않는다. 대신 복제본(standby)에서는 읽을 수 없다 — 이 테이블은 기본 노드만 쓴다.
-- seq는 대상별 단조 증가로 DB가 매긴다: 조회권이 노드를 옮겨 다녀도 화면의 결번 판정이 끊기지 않게.
CREATE UNLOGGED TABLE live_frame (
    instance_id BIGINT       PRIMARY KEY,
    seq         BIGINT       NOT NULL,
    frame       TEXT         NOT NULL,
    produced_at TIMESTAMPTZ  NOT NULL,
    producer    VARCHAR(200) NOT NULL
);
