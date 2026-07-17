-- 백업 정책을 (인스턴스, 타입)별로 — FULL 앵커 + LOG 체인 병행 스케줄이 현업 정석인데
-- 인스턴스당 1정책 제약이 그 조합을 막고 있었다(RPO는 LOG 주기가, RTO는 FULL 최신성이 정한다).
-- 기존 unique(instance_id)는 환경마다 이름이 다르다(V1 명명 vs Hibernate 생성명) — 동적으로 찾아 제거.
DO $$
DECLARE con record;
BEGIN
    FOR con IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'backup_policy_entity'::regclass AND contype = 'u'
    LOOP
        EXECUTE format('ALTER TABLE backup_policy_entity DROP CONSTRAINT %I', con.conname);
    END LOOP;
END $$;

ALTER TABLE backup_policy_entity
    ADD CONSTRAINT uk_backup_policy_instance_type UNIQUE (instance_id, type);

-- 정책 타입에 PHYSICAL 허용 — 물리 백업(87절)은 즉시 실행만 가능했고 정책 스케줄은 CHECK가 막고 있었다
ALTER TABLE backup_policy_entity DROP CONSTRAINT IF EXISTS backup_policy_entity_type_check;
ALTER TABLE backup_policy_entity
    ADD CONSTRAINT backup_policy_entity_type_check CHECK (type IN ('FULL', 'LOG', 'PHYSICAL'));
