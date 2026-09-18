-- V49: ash_sample·ash_sample_tick 을 일별 RANGE 파티셔닝 (#149)
--
-- 왜: V18 이 query_snapshot 을 파티션으로 바꾸며 적은 근거가 이 표에 더 크게 해당한다.
-- 그 표는 60초 주기로 하루 72만 행인데, ash_sample 은 1초 주기라 인스턴스 5대·틱당 5세션
-- 기준으로 하루 216만 행이다(상한 500세션까지 가면 2억 행). 더 많이 쌓이는 표가 배치 DELETE 로
-- 남아 있어 dead tuple 블로트를 매일 만들고 있었다
-- (V18 실측: 200만 행 DELETE 1.9초, VACUUM 후에도 공간 미반환).
--
-- 왜 월이 아니라 일인가: 보존이 7일이라서다. 파티션은 그 구간 전체가 기한 밖이 되어야 DROP 할 수 있다.
--   단위   한 장 행수     최악 상주      파티션 수
--   일     216만         8일  1,728만     9
--   주     1,512만       14일 3,024만     3
--   월     6,480만       37일 7,992만     2
-- 월로 하면 7일 보존인데도 최악 37일치를 들고 있게 된다 — 보존 설정이 사실상 무의미해진다.
-- query_snapshot 이 월인 것은 그 표가 하루 72만 행이라 월 파티션 최악 상주가 2,664만 행으로
-- 감당되기 때문이다. 같은 규칙이 아니라 같은 기준(최악 상주 행수)을 적용한 결과가 다른 것이다.
--
-- 전환 방식은 V18·V19 와 같다: 신 테이블 생성 -> 복사 -> 스왑(Flyway 단일 트랜잭션).
-- PK 는 파티션 키를 포함해야 해서 (id, sampled_at) 이다. 이 표는 배치 INSERT 와
-- (instance_id, sampled_at) 범위 조회만 쓰므로 엔티티 @Id(id) 와의 차이는 조회에 영향이 없다.
--
-- ash 샘플러는 기본 꺼짐(dbtower.ash.enabled=false)이라 대부분의 설치에서 이 표는 비어 있다.
-- 그래도 지금 바꾸는 이유는, 켜고 나서 바꾸면 그때는 데이터가 쌓인 상태의 전환이 되기 때문이다.

CREATE SEQUENCE ash_sample_part_id_seq;

CREATE TABLE ash_sample_part (
    id                BIGINT          NOT NULL DEFAULT nextval('ash_sample_part_id_seq'),
    instance_id       BIGINT          NOT NULL,
    sampled_at        TIMESTAMP(3)    NOT NULL,
    ingested_at       TIMESTAMP(3)    NOT NULL,
    sampler_run_id    VARCHAR(36)     NOT NULL,
    sample_seq        BIGINT          NOT NULL,
    pid               BIGINT          NOT NULL,
    username          VARCHAR(128),
    state             VARCHAR(64),
    wait_event        VARCHAR(255),
    wait_category     VARCHAR(64),
    blocked_by_pid    BIGINT,
    query_fingerprint VARCHAR(32),
    elapsed_ms        DOUBLE PRECISION,
    CONSTRAINT ash_sample_part_pkey PRIMARY KEY (id, sampled_at),
    CONSTRAINT fk_ash_sample_part_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
) PARTITION BY RANGE (sampled_at);

ALTER SEQUENCE ash_sample_part_id_seq OWNED BY ash_sample_part.id;

CREATE TABLE ash_sample_pdefault PARTITION OF ash_sample_part DEFAULT;

CREATE SEQUENCE ash_sample_tick_part_id_seq;

CREATE TABLE ash_sample_tick_part (
    id                BIGINT          NOT NULL DEFAULT nextval('ash_sample_tick_part_id_seq'),
    instance_id       BIGINT          NOT NULL,
    sampled_at        TIMESTAMP(3)    NOT NULL,
    ingested_at       TIMESTAMP(3)    NOT NULL,
    sampler_run_id    VARCHAR(36)     NOT NULL,
    sample_seq        BIGINT          NOT NULL,
    observed_sessions INTEGER         NOT NULL,
    retained_sessions INTEGER         NOT NULL,
    dropped_sessions  INTEGER         NOT NULL,
    collect_ms        DOUBLE PRECISION NOT NULL,
    status            VARCHAR(24)     NOT NULL,
    error_message     VARCHAR(500),
    CONSTRAINT ash_sample_tick_part_pkey PRIMARY KEY (id, sampled_at),
    CONSTRAINT fk_ash_sample_tick_part_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
) PARTITION BY RANGE (sampled_at);

ALTER SEQUENCE ash_sample_tick_part_id_seq OWNED BY ash_sample_tick_part.id;

CREATE TABLE ash_sample_tick_pdefault PARTITION OF ash_sample_tick_part DEFAULT;

-- 기존 데이터가 걸치는 모든 날 + 오늘 + 내일 파티션 생성
DO $$
DECLARE
    d     DATE;
    fromD DATE;
    toD   DATE;
BEGIN
    SELECT COALESCE(MIN(sampled_at)::DATE, CURRENT_DATE),
           COALESCE(MAX(sampled_at)::DATE, CURRENT_DATE)
      INTO fromD, toD
      FROM (SELECT sampled_at FROM ash_sample
            UNION ALL
            SELECT sampled_at FROM ash_sample_tick) s;

    d := LEAST(fromD, CURRENT_DATE);
    WHILE d <= GREATEST(toD, CURRENT_DATE + 1) LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS ash_sample_part_y%sm%sd%s PARTITION OF ash_sample_part '
            || 'FOR VALUES FROM (%L) TO (%L)',
            to_char(d, 'YYYY'), to_char(d, 'MM'), to_char(d, 'DD'), d, d + 1);
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS ash_sample_tick_part_y%sm%sd%s PARTITION OF ash_sample_tick_part '
            || 'FOR VALUES FROM (%L) TO (%L)',
            to_char(d, 'YYYY'), to_char(d, 'MM'), to_char(d, 'DD'), d, d + 1);
        d := d + 1;
    END LOOP;
END $$;

INSERT INTO ash_sample_part (id, instance_id, sampled_at, ingested_at, sampler_run_id, sample_seq,
                             pid, username, state, wait_event, wait_category, blocked_by_pid,
                             query_fingerprint, elapsed_ms)
SELECT id, instance_id, sampled_at, ingested_at, sampler_run_id, sample_seq,
       pid, username, state, wait_event, wait_category, blocked_by_pid,
       query_fingerprint, elapsed_ms
  FROM ash_sample;

INSERT INTO ash_sample_tick_part (id, instance_id, sampled_at, ingested_at, sampler_run_id, sample_seq,
                                  observed_sessions, retained_sessions, dropped_sessions,
                                  collect_ms, status, error_message)
SELECT id, instance_id, sampled_at, ingested_at, sampler_run_id, sample_seq,
       observed_sessions, retained_sessions, dropped_sessions, collect_ms, status, error_message
  FROM ash_sample_tick;

SELECT setval('ash_sample_part_id_seq', COALESCE((SELECT MAX(id) FROM ash_sample_part), 0) + 1, false);
SELECT setval('ash_sample_tick_part_id_seq', COALESCE((SELECT MAX(id) FROM ash_sample_tick_part), 0) + 1, false);

DROP TABLE ash_sample;
DROP TABLE ash_sample_tick;

ALTER TABLE ash_sample_part RENAME TO ash_sample;
ALTER TABLE ash_sample_tick_part RENAME TO ash_sample_tick;

-- 부모를 RENAME 해도 자식 이름은 그대로다. 자식이 `ash_sample_part_y...` 로 남으면
-- PartitionLifecycle.droppable 의 `childName.startsWith(table + "_y")` 에 걸리지 않아
-- 보존 스윕이 영원히 지나친다 — 오류 없이 안 지워지는 쪽이라 배포 뒤에는 보이지 않는다(#149).
DO $$
DECLARE
    child TEXT;
BEGIN
    FOR child IN
        SELECT c.relname FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname IN ('ash_sample', 'ash_sample_tick')
          AND c.relname LIKE '%\_part\_y%'
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME TO %I', child, replace(child, '_part_y', '_y'));
    END LOOP;
END $$;

-- 인덱스는 파티션 부모에 만들면 자식에 전파된다. 이름은 V32 와 같게 둔다.
CREATE INDEX idx_ash_sample_instance_time ON ash_sample (instance_id, sampled_at);
-- 부분 인덱스도 파티션드 테이블에서 지원된다 — 막힌 세션만 훑는 목적 그대로다.
CREATE INDEX idx_ash_sample_blocked ON ash_sample (instance_id, sampled_at)
    WHERE blocked_by_pid IS NOT NULL;
-- UNIQUE 는 파티션 키를 포함해야 한다. sampled_at 이 이미 들어 있어 V32 와 같은 뜻이 유지된다.
CREATE UNIQUE INDEX uq_ash_sample_tick_pid ON ash_sample (instance_id, sampled_at, pid);

CREATE INDEX idx_ash_sample_tick_instance_time ON ash_sample_tick (instance_id, sampled_at);
CREATE UNIQUE INDEX uq_ash_sample_tick ON ash_sample_tick (instance_id, sampled_at);
