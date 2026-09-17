package io.dbtower.workbench.internal.persistence;

import io.dbtower.workbench.internal.domain.BulkChangeBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BulkChangeBatchRepository extends JpaRepository<BulkChangeBatch, Long> {

    List<BulkChangeBatch> findByReviewIdOrderByBatchNoAsc(Long reviewId);

    /** 화면은 마지막 몇 개만 보여준다 — 배치가 수천 개면 전부 내려보내는 것이 곧 사고다 */
    List<BulkChangeBatch> findTop50ByReviewIdOrderByBatchNoDesc(Long reviewId);
}
