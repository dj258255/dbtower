-- 인스턴스 조직 태그: 환경/리전/클러스터 (레퍼런스의 환경·리전·클러스터 선택 대응).
-- AWS RDS 고유 개념이지만 셀프호스트 이기종에도 일반화해 담는다 — 환경(prod/staging/dev),
-- 리전(ap-northeast-2·on-prem-dc1 등 자유 라벨), 클러스터(복제 그룹·서비스 묶음 라벨).
-- 전부 선택(null=미지정) — 강제 아니라 필터·표기용. team_label과 같은 운영 메타 성격이라 같은 자리에 붙인다.
ALTER TABLE database_instance ADD COLUMN environment VARCHAR(50);
ALTER TABLE database_instance ADD COLUMN region VARCHAR(50);
ALTER TABLE database_instance ADD COLUMN cluster_label VARCHAR(100);
