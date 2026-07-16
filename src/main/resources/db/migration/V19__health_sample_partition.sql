-- V19: health_sample 월별 RANGE 파티셔닝 — V18(query_snapshot)과 같은 패턴.
-- 볼륨은 query_snapshot의 1/100 수준이지만 보존이 35일로 길어 dead tuple 블로트가 더 오래 산다 —
-- 월 파티션 DROP이면 블로트 수명이 유한해진다. 절차·제약(신테이블→복사→스왑, PG 16 identity
-- 미지원 → 시퀀스 DEFAULT, PK에 파티션 키 포함)은 V18 주석 참고.

CREATE SEQUENCE health_sample_part_id_seq;

CREATE TABLE health_sample_part (
    id          BIGINT       NOT NULL DEFAULT nextval('health_sample_part_id_seq'),
    instance_id BIGINT       NOT NULL,
    sampled_at  TIMESTAMP(6) NOT NULL,
    up          BOOLEAN      NOT NULL,
    ping_millis BIGINT       NOT NULL,
    CONSTRAINT health_sample_part_pkey PRIMARY KEY (id, sampled_at),
    -- V10의 인스턴스 삭제 캐스케이드 유지
    CONSTRAINT fk_health_sample_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
) PARTITION BY RANGE (sampled_at);

ALTER SEQUENCE health_sample_part_id_seq OWNED BY health_sample_part.id;

CREATE TABLE health_sample_pdefault PARTITION OF health_sample_part DEFAULT;

DO $$
DECLARE
    m  DATE;
    fromM DATE;
    toM   DATE;
BEGIN
    SELECT date_trunc('month', COALESCE(min(sampled_at), now()))::date INTO fromM FROM health_sample;
    toM := (date_trunc('month', now()) + interval '1 month')::date;
    m := fromM;
    WHILE m <= toM LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF health_sample_part FOR VALUES FROM (%L) TO (%L)',
            'health_sample_y' || to_char(m, 'YYYY') || 'm' || to_char(m, 'MM'),
            m, (m + interval '1 month')::date);
        m := (m + interval '1 month')::date;
    END LOOP;
END $$;

INSERT INTO health_sample_part (id, instance_id, sampled_at, up, ping_millis)
SELECT id, instance_id, sampled_at, up, ping_millis FROM health_sample;

SELECT setval('health_sample_part_id_seq', COALESCE((SELECT max(id) FROM health_sample_part), 1));

DROP TABLE health_sample;
ALTER TABLE health_sample_part RENAME TO health_sample;
ALTER INDEX health_sample_part_pkey RENAME TO health_sample_pkey;

CREATE INDEX idx_health_sample_instance_time ON health_sample (instance_id, sampled_at);
