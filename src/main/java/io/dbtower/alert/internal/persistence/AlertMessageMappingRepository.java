package io.dbtower.alert.internal.persistence;

import io.dbtower.alert.internal.domain.AlertMessageMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface AlertMessageMappingRepository extends JpaRepository<AlertMessageMapping, String> {

    /** 보존 정리 — 반응은 보통 알림 직후에 달리므로 오래된 매핑은 의미가 없다. */
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
