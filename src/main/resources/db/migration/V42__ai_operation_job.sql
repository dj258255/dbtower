-- AI 운영 작업의 공통 상태 (VERIFICATION 169절)
--
-- 외부 게이트웨이·실행기는 이 테이블을 직접 갱신하지 않고 REST 서비스의 전이를 쓴다.
-- requester와 submitted_by를 나누는 이유: Slack 게이트웨이가 서비스 토큰으로 올리면 인증 주체는 api-token이지만
-- 요청한 사람은 Slack 사용자다. 한 칸에 섞으면 감사에서 둘 중 하나를 잃는다.
-- scope_team은 접수 시점의 팀 범위다. 실행기는 전역 서비스 토큰으로 돌기 때문에, 선점할 때 이 값으로 범위를 다시 확인한다.
-- lease_token은 같은 이벤트가 두 실행기에 배달돼도 먼저 선점한 쪽만 이후 단계를 진행하게 한다(Redis는 최소 1회 배달).
CREATE TABLE ai_operation_job (
    job_id         VARCHAR(36)  PRIMARY KEY,
    request_id     VARCHAR(120) NOT NULL UNIQUE,
    type           VARCHAR(40)  NOT NULL,
    trigger_source VARCHAR(20)  NOT NULL,
    requester      VARCHAR(200) NOT NULL,
    submitted_by   VARCHAR(200) NOT NULL,
    scope_team     VARCHAR(100),
    instance_id    BIGINT,
    -- 접수 시점의 기종. 실행기가 참고 자료를 기종으로 거르는 데 쓴다(MySQL 진단에 PostgreSQL 런북을 붙이지 않게)
    instance_type  VARCHAR(20),
    window_from    TIMESTAMPTZ  NOT NULL,
    window_to      TIMESTAMPTZ  NOT NULL,
    prompt         TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    attempt        INTEGER      NOT NULL,
    lease_token    VARCHAR(36),
    lease_until    TIMESTAMPTZ,
    reply_channel  VARCHAR(100),
    reply_thread   VARCHAR(100),
    notified_at    TIMESTAMPTZ,
    requested_at   TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    failure_reason VARCHAR(500),
    version        BIGINT       NOT NULL
);

-- 리퍼(리스 만료 정리)와 목록 화면이 상태·갱신 시각으로 찾는다
CREATE INDEX ai_operation_job_status_updated_idx ON ai_operation_job (status, updated_at);
-- 요청자별 진행 중 작업 수 상한 검사
CREATE INDEX ai_operation_job_requester_idx ON ai_operation_job (requester, status);
