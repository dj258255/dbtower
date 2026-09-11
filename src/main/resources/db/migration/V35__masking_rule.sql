-- 워크벤치 결과 마스킹 규칙 — 결과를 만드는 단계에서 컬럼 이름으로 값을 가린다(SQL을 고치지 않는다).
-- instance_id가 NULL이면 전 인스턴스 공통. 인스턴스 규칙이 공통 규칙보다 먼저 적용된다.
-- 이름 기반이라 표현식(CONCAT(email, ''))은 못 잡는다 — 본 방어선은 콘솔 계정의 컬럼 단위 권한이다.
CREATE TABLE masking_rule (
    id              BIGSERIAL PRIMARY KEY,
    instance_id     BIGINT       REFERENCES database_instance (id) ON DELETE CASCADE,
    column_pattern  VARCHAR(100) NOT NULL,     -- 소문자 glob, 예: *email*
    strategy        VARCHAR(16)  NOT NULL CHECK (strategy IN ('FULL', 'PARTIAL', 'HASH')),
    note            VARCHAR(200),
    created_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_masking_rule_instance ON masking_rule (instance_id);

-- 공통 기본 규칙: 인증 비밀(전부 가림), 식별번호(전부 가림), 연락처·계좌(일부만 보임)
INSERT INTO masking_rule (instance_id, column_pattern, strategy, note, created_at) VALUES
    (NULL, '*password*',     'FULL',    '인증 비밀', NOW()),
    (NULL, '*passwd*',       'FULL',    '인증 비밀', NOW()),
    (NULL, '*secret*',       'FULL',    '인증 비밀', NOW()),
    (NULL, '*token*',        'FULL',    '인증 비밀', NOW()),
    (NULL, '*api_key*',      'FULL',    '인증 비밀', NOW()),
    (NULL, '*rrn*',          'FULL',    '주민등록번호', NOW()),
    (NULL, '*jumin*',        'FULL',    '주민등록번호', NOW()),
    (NULL, '*resident*',     'FULL',    '주민등록번호', NOW()),
    (NULL, '*ssn*',          'FULL',    '사회보장번호', NOW()),
    (NULL, '*card_no*',      'PARTIAL', '카드번호', NOW()),
    (NULL, '*card_number*',  'PARTIAL', '카드번호', NOW()),
    (NULL, '*account_no*',   'PARTIAL', '계좌번호', NOW()),
    (NULL, '*phone*',        'PARTIAL', '전화번호', NOW()),
    (NULL, '*mobile*',       'PARTIAL', '휴대전화', NOW()),
    (NULL, '*email*',        'PARTIAL', '이메일', NOW()),
    (NULL, '*birth*',        'PARTIAL', '생년월일', NOW()),
    (NULL, '*address*',      'PARTIAL', '주소', NOW());
