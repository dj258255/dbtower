package io.dbtower.aiops.internal.persistence;

import io.dbtower.aiops.internal.domain.AiOperationResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiOperationResultRepository extends JpaRepository<AiOperationResultEntity, String> {
}
