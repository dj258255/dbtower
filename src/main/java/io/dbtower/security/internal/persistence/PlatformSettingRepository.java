package io.dbtower.security.internal.persistence;

import io.dbtower.security.internal.domain.PlatformSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {
}
