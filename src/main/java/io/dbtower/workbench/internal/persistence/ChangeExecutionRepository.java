package io.dbtower.workbench.internal.persistence;

import io.dbtower.workbench.internal.domain.ChangeExecution;
import io.dbtower.workbench.internal.domain.ChangeExecution.Action;
import io.dbtower.workbench.internal.domain.ChangeExecution.Outcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ChangeExecutionRepository extends JpaRepository<ChangeExecution, Long> {

    List<ChangeExecution> findByReviewIdOrderByStartedAtDesc(Long reviewId);

    /** 되돌리기의 원본 — 그 티켓의 마지막 커밋된 실행 */
    Optional<ChangeExecution> findFirstByReviewIdAndActionAndOutcomeOrderByStartedAtDesc(Long reviewId, Action action,
                                                                                         Outcome outcome);

    /** 보존 기한이 지난 행 사본을 지우고 되돌리기를 닫는다 — 사본은 개인정보를 담은 원본 값이라 무기한 두지 않는다 */
    @Modifying
    @Query("update ChangeExecution e set e.images = null, e.rollbackAvailable = false, e.rollbackNote = :note"
            + " where e.images is not null and e.imagesExpireAt < :now")
    int expireImages(@Param("now") LocalDateTime now, @Param("note") String note);
}
