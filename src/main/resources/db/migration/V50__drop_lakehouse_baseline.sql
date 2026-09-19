-- 외부 lakehouse 연동을 제거한다. 장기 분석은 메타 DB의 집계 보관으로 재설계한다.
-- V24는 이미 적용된 설치가 있으므로 고치지 않고, 수신 전용이던 테이블을 새 마이그레이션으로 없앤다.
DROP TABLE IF EXISTS baseline_longterm;
