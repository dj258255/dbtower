-- TLS 강제 접속 옵션 — Atlas·Azure SQL·RDS(rds.force_ssl) 같은 TLS 강제 관리형 서비스 대응.
-- 기존 행은 전부 FALSE(평문 접속 유지) — 하위 호환.
ALTER TABLE database_instance ADD COLUMN use_tls BOOLEAN NOT NULL DEFAULT FALSE;
