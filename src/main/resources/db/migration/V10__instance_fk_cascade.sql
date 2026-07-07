-- V10: 인스턴스 삭제 시 자식 데이터 정리 (WS-B B-1) — 삭제해도 query_snapshot·plan_snapshot·
-- backup_run·backup_policy_entity·health_sample 행이 영구히 남던 고아 데이터 누수를 막는다.
-- database_instance.id를 참조하는 다섯 자식 표의 instance_id에 FK ON DELETE CASCADE를 건다.
-- 이제 database_instance 한 행을 지우면 DB가 자식 행까지 한 번에 정리한다(RegistryService.delete는
-- deleteById만 하면 되고, 트랜잭션·정합성은 DB의 FK 무결성이 보증한다).
--
-- 기존 데이터 방어: FK를 걸기 전에 부모가 이미 사라진 고아 행(과거 삭제로 남은 것)을 먼저 지운다.
-- 고아가 남아 있으면 ALTER ... ADD CONSTRAINT 자체가 무결성 위반으로 실패하기 때문.
-- (엔티티에는 FK를 선언하지 않는다 — ddl-auto=validate는 컬럼/타입만 검증하고 FK 존재는 요구하지 않으므로
--  스키마의 단일 권위인 이 마이그레이션에만 둔다.)

DELETE FROM query_snapshot     WHERE instance_id NOT IN (SELECT id FROM database_instance);
DELETE FROM plan_snapshot      WHERE instance_id NOT IN (SELECT id FROM database_instance);
DELETE FROM backup_run         WHERE instance_id NOT IN (SELECT id FROM database_instance);
DELETE FROM backup_policy_entity WHERE instance_id NOT IN (SELECT id FROM database_instance);
DELETE FROM health_sample      WHERE instance_id NOT IN (SELECT id FROM database_instance);

ALTER TABLE query_snapshot
    ADD CONSTRAINT fk_query_snapshot_instance
    FOREIGN KEY (instance_id) REFERENCES database_instance (id) ON DELETE CASCADE;

ALTER TABLE plan_snapshot
    ADD CONSTRAINT fk_plan_snapshot_instance
    FOREIGN KEY (instance_id) REFERENCES database_instance (id) ON DELETE CASCADE;

ALTER TABLE backup_run
    ADD CONSTRAINT fk_backup_run_instance
    FOREIGN KEY (instance_id) REFERENCES database_instance (id) ON DELETE CASCADE;

ALTER TABLE backup_policy_entity
    ADD CONSTRAINT fk_backup_policy_instance
    FOREIGN KEY (instance_id) REFERENCES database_instance (id) ON DELETE CASCADE;

ALTER TABLE health_sample
    ADD CONSTRAINT fk_health_sample_instance
    FOREIGN KEY (instance_id) REFERENCES database_instance (id) ON DELETE CASCADE;
