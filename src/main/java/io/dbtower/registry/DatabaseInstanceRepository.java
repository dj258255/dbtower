package io.dbtower.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DatabaseInstanceRepository extends JpaRepository<DatabaseInstance, Long> {

    /** 멱등 등록(upsert) 시 이름으로 기존 인스턴스를 찾는다 — 이름은 유니크(V1 baseline) */
    Optional<DatabaseInstance> findByName(String name);
}
