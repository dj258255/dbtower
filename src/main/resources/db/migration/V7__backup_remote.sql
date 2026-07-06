-- 백업 원격 보관 위치(3-2-1의 오프사이트) — 성공 백업을 S3 호환 스토리지에 업로드한 키.
-- NULL이면 원격 보관 안 됨(비활성 또는 업로드 실패) — 로컬 성공과 원격 보관을 구분해 기록한다.
ALTER TABLE backup_run ADD COLUMN remote_location VARCHAR(512);
