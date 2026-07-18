package io.dbtower.review.internal.persistence;

import io.dbtower.review.internal.domain.ReviewRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, Long> {

    /** 인스턴스별 최신순 — 콘솔 목록. */
    List<ReviewRequest> findByInstanceIdOrderBySubmittedAtDesc(Long instanceId);

    /** 상태별 최신순 — "대기 중 요청" 뷰. */
    List<ReviewRequest> findByStatusOrderBySubmittedAtDesc(ReviewRequest.Status status);
}
