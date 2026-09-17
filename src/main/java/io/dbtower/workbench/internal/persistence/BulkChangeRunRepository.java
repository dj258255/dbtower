package io.dbtower.workbench.internal.persistence;

import io.dbtower.workbench.internal.domain.BulkChangeRunRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BulkChangeRunRepository extends JpaRepository<BulkChangeRunRecord, Long> {

    /** 재기동 뒤 "무엇이 중단된 채 남았나" — 끝나지 않은 상태로 남은 실행 */
    List<BulkChangeRunRecord> findByStateIn(List<String> states);
}
