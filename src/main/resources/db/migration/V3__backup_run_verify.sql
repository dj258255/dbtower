-- V3: 백업 복원 검증 (Phase A7) — "테스트해 본 적 없는 백업은 백업이 아니다".
-- io.dbtower.backup.BackupRun에 열 추가. 적용된 스크립트는 불변 — 변경은 V4+ 로 추가한다.
--
-- 기존 backup_run은 산출물 위치를 detail 안에 "location (N bytes)" 꼴로 섞어 두었다.
-- 검증(가장 최근 백업을 다시 찾아 복원해 보기)에는 위치가 그 자체로 필요하므로 별도 열로 뽑는다.
-- verify_status는 3-값(VERIFIED/FAILED/UNSUPPORTED) — CHECK 제약은 걸지 않는다:
-- 값 추가 때 제약 재생성 마이그레이션이 필요해지는 결합(VERIFICATION 18-3의 enum 확장 사고)을 피한다.
ALTER TABLE backup_run ADD COLUMN location      VARCHAR(1000);
ALTER TABLE backup_run ADD COLUMN verify_status VARCHAR(20);
ALTER TABLE backup_run ADD COLUMN verified_at   TIMESTAMP(6);
