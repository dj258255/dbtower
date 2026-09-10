package io.dbtower.review.internal.persistence;

import io.dbtower.review.internal.domain.ReviewRequest;
import io.dbtower.review.internal.domain.ReviewRequest.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, Long> {

    /** 인스턴스별 최신순 — 콘솔 목록. */
    List<ReviewRequest> findByInstanceIdOrderBySubmittedAtDesc(Long instanceId);

    /** 상태별 최신순 — "대기 중 요청" 뷰. */
    List<ReviewRequest> findByStatusOrderBySubmittedAtDesc(ReviewRequest.Status status);

    /**
     * 조건부 상태 전이 — 현재 상태가 from일 때만 바꾸고 바뀐 행 수를 돌려준다. 읽고-판단하고-쓰는 사이에 다른 요청이 끼어도
     * 행 락 아래서 한 요청만 1을 받는다. 실행권(EXECUTING)을 이 쿼리로만 얻는 이유다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ReviewRequest r set r.status = :to where r.id = :id and r.status = :from")
    int transition(@Param("id") Long id, @Param("from") Status from, @Param("to") Status to);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ReviewRequest r set r.status = io.dbtower.review.internal.domain.ReviewRequest.Status.EXECUTED,"
            + " r.executedBy = :by, r.executedAt = :at"
            + " where r.id = :id and r.status = io.dbtower.review.internal.domain.ReviewRequest.Status.EXECUTING")
    int markExecuted(@Param("id") Long id, @Param("by") String by, @Param("at") LocalDateTime at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ReviewRequest r set r.status = io.dbtower.review.internal.domain.ReviewRequest.Status.ROLLED_BACK,"
            + " r.rolledBackBy = :by, r.rolledBackAt = :at"
            + " where r.id = :id and r.status = io.dbtower.review.internal.domain.ReviewRequest.Status.ROLLING_BACK")
    int markRolledBack(@Param("id") Long id, @Param("by") String by, @Param("at") LocalDateTime at);
}
