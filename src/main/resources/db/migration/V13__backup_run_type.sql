-- 백업 이력에 타입(FULL/LOG) 기록 (Phase 2 — PITR 복원 가능 범위 계산의 전제)
-- "마지막 FULL 시각 ~ 그 이후 마지막 LOG 시각"을 계산하려면 이력에 타입 구분이 있어야 한다.
-- 기존 행은 타입을 기록하지 않았으므로 null 유지 — FULL이었다고 위장하지 않는다(PITR 계산에서 제외).
ALTER TABLE backup_run ADD COLUMN backup_type VARCHAR(10);

-- 미지원(UNSUPPORTED)을 실패(FAILED)와 구분 — "기종이 못 하는 것"과 "하다가 깨진 것"은 다른 사실이다.
-- 이전엔 UnsupportedOperationException도 FAILED로 뭉개져 신선도·이력에서 실패로 보였다.
ALTER TABLE backup_run DROP CONSTRAINT IF EXISTS backup_run_status_check;
ALTER TABLE backup_run ADD CONSTRAINT backup_run_status_check
    CHECK (status IN ('SUCCESS', 'FAILED', 'UNSUPPORTED'));
