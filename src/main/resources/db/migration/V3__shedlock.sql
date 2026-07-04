-- V3: 분산 락 테이블 (Phase A5) — 다중 인스턴스(HA) 배포에서 @Scheduled 폴러가
-- 한 시점에 한 노드에서만 돌도록 ShedLock이 사용하는 락 레지스터.
-- 적용된 스크립트는 불변 — 변경은 V4+ 로 추가한다.
--
-- 컬럼 정의는 ShedLock 공식 PostgreSQL 스키마 그대로다(임의 변경 시 프로바이더가 깨진다):
--   name       = 락 이름(PK). 각 작업이 고정 문자열로 하나의 락을 공유한다.
--   lock_until = 이 시각까지 락 보유. 지나면 다른 노드가 재획득 가능(크래시 노드 회수의 안전망).
--   locked_at  = 획득 시각(관측/디버깅용).
--   locked_by  = 락을 쥔 노드 식별자(기본은 호스트명) — 어느 노드가 잡았는지 추적.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT shedlock_pkey PRIMARY KEY (name)
);
