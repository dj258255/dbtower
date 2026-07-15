-- 인스턴스 메타: 담당팀/Slack 라벨 + 콘솔 딥링크 (심화 아크 4 — 레퍼런스 "Slack Group / AWS Link" 대응)
-- team_label은 Phase 3 LBAC(팀 스코핑)와 같은 컬럼을 공유하도록 여기서 한 번만 추가한다(이중 마이그레이션 방지).
-- console_url은 조직이 쓰는 콘솔 아무거나(Grafana 대시보드·AWS PI·내부 위키) — AWS SDK 연동 대신 URL 일반화.
ALTER TABLE database_instance ADD COLUMN team_label VARCHAR(100);
ALTER TABLE database_instance ADD COLUMN console_url VARCHAR(500);
