package io.dbtower.workbench.internal.persistence;

import io.dbtower.workbench.internal.domain.SqlVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SqlVersionRepository extends JpaRepository<SqlVersion, Long> {

    List<SqlVersion> findByWorksheetIdOrderByVersionNoAsc(Long worksheetId);

    Optional<SqlVersion> findTopByWorksheetIdOrderByVersionNoDesc(Long worksheetId);

    Optional<SqlVersion> findByWorksheetIdAndVersionNo(Long worksheetId, int versionNo);
}
