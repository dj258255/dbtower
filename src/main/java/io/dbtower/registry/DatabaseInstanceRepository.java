package io.dbtower.registry;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DatabaseInstanceRepository extends JpaRepository<DatabaseInstance, Long> {
}
