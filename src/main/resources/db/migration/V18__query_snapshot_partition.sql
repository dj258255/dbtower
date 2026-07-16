-- V18: query_snapshot 월별 RANGE 파티셔닝 (Phase 4 — 메타 DB가 관리 대상보다 먼저 늙지 않게)
--
-- 왜: 최대 볼륨 테이블(인스턴스 5대 기준 하루 72만 행)의 보존 정리가 벌크 DELETE라
-- (1) 삭제 자체가 느리고 (2) dead tuple 블로트가 남는다(실측: 200만 행 DELETE 1.9초,
-- VACUUM 후에도 공간 미반환). 월별 파티션이면 지난 달 정리는 DROP TABLE — 즉시·블로트 없음.
--
-- 기존 테이블은 ALTER로 파티션 전환이 불가 — 신 테이블 생성 -> 복사 -> 스왑(Flyway 단일 트랜잭션).
-- 대량 데이터 운영 전환은 잠금 시간이 곧 수집 중단 시간이므로 창을 골라 실행한다(docs/operations.md).
-- PG 16은 파티션 테이블에 identity를 지원하지 않아(17부터) 시퀀스 DEFAULT로 대체한다.
-- PK는 파티션 키를 포함해야 해서 (id, captured_at) — 엔티티 @Id(id)와의 차이는 조회에 영향 없다
-- (이 테이블은 배치 INSERT와 (instance_id, captured_at) 범위 조회만 쓴다).

CREATE SEQUENCE query_snapshot_part_id_seq;

CREATE TABLE query_snapshot_part (
    id            BIGINT           NOT NULL DEFAULT nextval('query_snapshot_part_id_seq'),
    instance_id   BIGINT           NOT NULL,
    captured_at   TIMESTAMP(6)     NOT NULL,
    query_id      VARCHAR(64)      NOT NULL,
    query_text    VARCHAR(4000),
    calls         BIGINT           NOT NULL,
    total_time_ms DOUBLE PRECISION NOT NULL,
    rows_examined BIGINT           NOT NULL,
    CONSTRAINT query_snapshot_part_pkey PRIMARY KEY (id, captured_at),
    -- V10의 인스턴스 삭제 캐스케이드 유지
    CONSTRAINT fk_query_snapshot_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
) PARTITION BY RANGE (captured_at);

ALTER SEQUENCE query_snapshot_part_id_seq OWNED BY query_snapshot_part.id;

-- 파티션 미생성 월의 INSERT가 실패하지 않게 하는 안전망 — 정상 경로는 월 파티션에 쌓이고,
-- 유지 잡(SnapshotRetentionJob)이 이번 달·다음 달 파티션을 선생성한다.
CREATE TABLE query_snapshot_pdefault PARTITION OF query_snapshot_part DEFAULT;

-- 기존 데이터가 걸치는 모든 월 + 이번 달 + 다음 달 파티션 생성
DO $$
DECLARE
    m  DATE;
    fromM DATE;
    toM   DATE;
BEGIN
    SELECT date_trunc('month', COALESCE(min(captured_at), now()))::date INTO fromM FROM query_snapshot;
    toM := (date_trunc('month', now()) + interval '1 month')::date;
    m := fromM;
    WHILE m <= toM LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF query_snapshot_part FOR VALUES FROM (%L) TO (%L)',
            'query_snapshot_y' || to_char(m, 'YYYY') || 'm' || to_char(m, 'MM'),
            m, (m + interval '1 month')::date);
        m := (m + interval '1 month')::date;
    END LOOP;
END $$;

INSERT INTO query_snapshot_part (id, instance_id, captured_at, query_id, query_text, calls, total_time_ms, rows_examined)
SELECT id, instance_id, captured_at, query_id, query_text, calls, total_time_ms, rows_examined
FROM query_snapshot;

SELECT setval('query_snapshot_part_id_seq', COALESCE((SELECT max(id) FROM query_snapshot_part), 1));

DROP TABLE query_snapshot;
ALTER TABLE query_snapshot_part RENAME TO query_snapshot;
ALTER INDEX query_snapshot_part_pkey RENAME TO query_snapshot_pkey;

-- (instance_id, captured_at) 복합 인덱스 — 파티션드 인덱스로 재생성(각 파티션에 자동 전파)
CREATE INDEX idx_snapshot_instance_time ON query_snapshot (instance_id, captured_at);
