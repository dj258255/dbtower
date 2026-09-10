package io.dbtower.workbench.internal.persistence;

import io.dbtower.workbench.internal.domain.WorkbenchSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkbenchSettingRepository extends JpaRepository<WorkbenchSetting, Long> {
}
