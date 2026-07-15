-- 팀 라벨 기반 접근 스코핑(LBAC, Phase 3) — 사용자에 팀을 달면 그 팀 인스턴스(+ 전역 인스턴스)만 보인다.
-- database_instance.team_label(V12)과 짝. null = 전역(기존 사용자 하위 호환 — 스코프 없음).
ALTER TABLE platform_user ADD COLUMN team_label VARCHAR(100);
