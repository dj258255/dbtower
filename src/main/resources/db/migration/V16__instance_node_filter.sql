-- 인스턴스-노드 매핑 (Phase 5 디스크 예측) — 이 DB가 올라간 호스트의 node_exporter를 가리키는
-- Prometheus 라벨 셀렉터(예: instance="db-node-3:9100"). 디스크는 DB가 아니라 호스트 자원이라
-- 별도 매핑이 필요하다. null = 미지정(단일 노드 데모에선 전 노드 집계가 곧 그 노드).
ALTER TABLE database_instance ADD COLUMN node_filter VARCHAR(200);
