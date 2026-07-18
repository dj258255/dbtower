-- 장기 요일×시간대 베이스라인 — lakehouse가 되쓰는(writeback) 수신 테이블 (Phase 5 reverse ETL, D8).
--
-- 왜: BaselineService의 베이스라인은 14일 창이라 "매주 월요일 아침 배치 피크" 같은
-- 주간 계절성의 관측이 버킷당 2개뿐이다 — 관측 부족(학습 중)으로 판정이 보류되거나,
-- 표본이 얕아 평범한 주기 부하를 이상으로 오탐한다. lakehouse는 같은 데이터의 수개월
-- 이력을 갖고 있으므로, 그쪽이 계산한 (instance, query, dow, hour)별 시간당 호출량
-- 통계를 이 테이블로 받아 단기 베이스라인에 가중 병합한다.
--
-- 소유권: 스키마(DDL)는 이 마이그레이션(DBTower)이 소유하고, 행(데이터)은 lakehouse의
-- writeback이 소유한다(단일 트랜잭션 DELETE+INSERT — 폴러는 MVCC로 이전 버전을 본다).
-- 스키마 계약의 단일 진실은 lakehouse docs/CONTRACT.md §1-2 — 두 저장소가 이 형태를 공유한다.
--
-- 계정: 쓰기는 lakehouse_writer(이 테이블만 SELECT/INSERT/DELETE) 전용. 역할 생성·GRANT는
-- 환경 소유(Ansible/운영 절차)라 마이그레이션에 넣지 않는다 — infra/ansible 참조.
--
-- 지표 단위: mean/stddev는 "시간당 delta_calls"(그 시간대 호출 증가량). BaselineService가
-- QPS와 병합할 때 /3600으로 스케일한다(스케일은 소비자 몫 — 원자료는 원단위 보존).
CREATE TABLE baseline_longterm (
    instance_id        BIGINT           NOT NULL,
    query_id           VARCHAR(255)     NOT NULL,
    dow                SMALLINT         NOT NULL,  -- UTC, 일=0 .. 토=6 (DuckDB dayofweek 규약)
    hour               SMALLINT         NOT NULL,  -- 0-23 (UTC)
    mean_delta_calls   DOUBLE PRECISION,
    stddev_delta_calls DOUBLE PRECISION,           -- 관측 1개 버킷은 NULL(분산 정보 없음)
    observations       BIGINT,
    computed_at        TIMESTAMPTZ,
    PRIMARY KEY (instance_id, query_id, dow, hour)
);

-- 조회 패턴: 이상 감지 스캔이 (인스턴스, 지금 요일, 지금 시간대) 버킷을 통째로 읽는다.
CREATE INDEX idx_baseline_longterm_bucket ON baseline_longterm (instance_id, dow, hour);
