-- 플랫폼 자체 설정 키-값 저장소 (Phase 1). 재시작에도 살아남아야 하는 소량의 운영 값.
-- 첫 용도: API 토큰 — 미설정 시 매 기동 랜덤 재생성되어 MCP 연동이 재시작마다 깨지던 것을,
-- 최초 생성 토큰을 여기 저장해 재시작 생존시킨다.
CREATE TABLE platform_setting (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(512) NOT NULL,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
