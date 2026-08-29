-- 활성 세션 샘플링(ASH) 영속 — "지금 누가 누구를 막고 있나"를 장애가 끝난 뒤에도 답하기 위해.
--
-- 왜: SessionInfo(pid·user·state·waitEvent·blockedByPid·query·elapsedMs)는 5기종 전부
-- 구현돼 있는데 어디에도 남지 않는다. InsightController가 화면에 "지금"만 보여주고,
-- 영속되는 건 wait_event_snapshot(5분 주기, event별 집계 TOP 50)뿐이다. 집계는 "무엇이
-- 많았나"에 답하지만 "누가 누구를 막았나"에는 원리적으로 답하지 못한다 — 세션도 쿼리도
-- 블로커도 GROUP BY에서 이미 사라진 뒤다.
--
-- 선례: Oracle ASH(1초 샘플, SGA 순환 버퍼 → AWR로 10:1 축소), AWS RDS Performance
-- Insights(1초), SolarWinds DPA(every active session, every second), pgsentinel(ring buffer).
-- 짧은 보존(샘플) + 긴 보존(집계)로 나누는 것이 이 카테고리의 표준이고, 그 구조가 곧
-- DBTower(7일) + lakehouse(장기)다. lakehouse는 AWR 자리에 있다.
--
-- 한계(정직 표기): pgsentinel은 shared_preload_libraries 등록과 재시작이 필요한 확장이라
-- 밖에서 붙는 DBTower는 강제할 수 없다. 그래서 외부 폴링 샘플링을 쓴다(Datadog·DPA와 같은
-- 방식). 인프로세스 샘플링보다 정확도가 낮고 샘플 간격보다 짧은 대기는 놓친다.

-- 세션 샘플 한 행 = 한 시점의 한 세션.
CREATE TABLE ash_sample (
    id                BIGSERIAL       PRIMARY KEY,
    instance_id       BIGINT          NOT NULL,
    -- event time: 샘플러가 이 틱을 찍기로 한 시각. 롤업·대사의 기준축은 항상 이쪽이다.
    sampled_at        TIMESTAMP(3)    NOT NULL,
    -- processing time: 실제로 저장된 시각. (ingested_at - sampled_at)이 곧 e2e 지연이고,
    -- 이 둘을 하나로 합치면 늦은 도착을 영원히 관측할 수 없다.
    ingested_at       TIMESTAMP(3)    NOT NULL,
    -- 샘플러 JVM 1회 기동 = 1 run. 재기동하면 seq가 1부터 다시 시작하므로,
    -- 결번 판정은 반드시 (run_id, instance_id) 안에서만 한다.
    sampler_run_id    VARCHAR(36)     NOT NULL,
    -- 인스턴스별 단조 증가. 결측은 "없는 행"이라 그 자체로는 보이지 않고 결번으로만 보인다.
    sample_seq        BIGINT          NOT NULL,
    pid               BIGINT          NOT NULL,
    username          VARCHAR(128),
    state             VARCHAR(64),
    -- NULL이면 대기가 아니라 실제 실행 중(CPU). WaitEvent의 'CPU (대기 아님)' 표기와 같은 의미.
    wait_event        VARCHAR(255),
    wait_category     VARCHAR(64),
    -- 블로킹 트리의 간선. 이 컬럼 하나 때문에 이 테이블이 존재한다.
    blocked_by_pid    BIGINT,
    -- 쿼리 원문을 저장하지 않는 이유 둘: (1) 2000자 절단본을 초당 수천 행 쌓으면 부피가
    -- 폭증하고 (2) 파라미터에 개인정보가 실려 올 수 있다. 정규화 후 SHA-256 앞 16헥스만 남긴다.
    query_fingerprint VARCHAR(32),
    elapsed_ms        DOUBLE PRECISION,
    CONSTRAINT fk_ash_sample_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
);

-- 조회·추출 패턴: 인스턴스별 시간창(lakehouse offload 인덱스 선두 원칙과 동일).
CREATE INDEX idx_ash_sample_instance_time ON ash_sample (instance_id, sampled_at);
-- 블로킹 체인 판정 전용 — 막힌 세션만 훑는다(대부분의 행은 blocked_by_pid IS NULL).
CREATE INDEX idx_ash_sample_blocked ON ash_sample (instance_id, sampled_at)
    WHERE blocked_by_pid IS NOT NULL;
-- 멱등 재적재와 중복률 계측의 기준키. 같은 틱의 같은 세션은 한 번만 존재해야 한다.
CREATE UNIQUE INDEX uq_ash_sample_tick_pid ON ash_sample (instance_id, sampled_at, pid);

-- 샘플 시도 1회의 메타. 세션이 0건이어도 "쟀다"는 사실 자체가 남아야 한다.
--
-- 왜 따로 두나: 0은 성공과 실패가 같은 모양이다. ash_sample에 행이 없을 때 그것이
-- "활성 세션이 정말 없었다"인지 "샘플러가 못 돌았다"인지 구분할 수 없다. 이 표가 그 차이를
-- 만든다. 그리고 AWS가 db.load.avg(보정값)와 db.sampledload.avg(원값)를 따로 노출하듯,
-- 우리도 "봤다"와 "남겼다"를 분리해 떨어뜨린 사실을 숨기지 않는다.
CREATE TABLE ash_sample_tick (
    id                BIGSERIAL       PRIMARY KEY,
    instance_id       BIGINT          NOT NULL,
    sampled_at        TIMESTAMP(3)    NOT NULL,
    ingested_at       TIMESTAMP(3)    NOT NULL,
    sampler_run_id    VARCHAR(36)     NOT NULL,
    sample_seq        BIGINT          NOT NULL,
    -- 원값: 대상 DB가 돌려준 활성 세션 수.
    observed_sessions INTEGER         NOT NULL,
    -- 보정값: 실제로 저장한 수. observed > cap이면 elapsed 내림차순으로 잘라낸다.
    retained_sessions INTEGER         NOT NULL,
    -- observed - retained. 0이 아니면 이 틱은 전수가 아니다.
    dropped_sessions  INTEGER         NOT NULL,
    -- 샘플링 쿼리 왕복에 걸린 시간. A9 원칙("조회 자체가 부하가 되면 안 된다") 검증축.
    collect_ms        DOUBLE PRECISION NOT NULL,
    -- OK / SKIPPED_INFLIGHT(직전 틱이 아직 안 끝남) / ERROR
    status            VARCHAR(24)     NOT NULL,
    error_message     VARCHAR(500),
    CONSTRAINT fk_ash_sample_tick_instance FOREIGN KEY (instance_id)
        REFERENCES database_instance (id) ON DELETE CASCADE
);

CREATE INDEX idx_ash_sample_tick_instance_time ON ash_sample_tick (instance_id, sampled_at);
CREATE UNIQUE INDEX uq_ash_sample_tick ON ash_sample_tick (instance_id, sampled_at);
