-- 수집 활성화 토글 (Phase F, 스케일 제어) — 문제 인스턴스를 삭제하지 않고 일시 격리하는 스위치.
-- false면 스냅샷 수집·운영 경보 폴러가 이 인스턴스를 건너뛴다(등록 정보는 유지).
-- 기존 행은 모두 관제 중이었으므로 true로 백필하고, 이후 기본값도 true(등록 즉시 관제).
ALTER TABLE database_instance
    ADD COLUMN collection_enabled BOOLEAN NOT NULL DEFAULT TRUE;
