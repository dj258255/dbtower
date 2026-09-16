package io.dbtower.aiops.internal.persistence;

import io.dbtower.aiops.AiOperationStatus;
import io.dbtower.aiops.internal.domain.AiOperationJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiOperationJobRepository extends JpaRepository<AiOperationJob, String> {

    Optional<AiOperationJob> findByRequestId(String requestId);

    long countByRequesterAndStatusIn(String requester, Collection<AiOperationStatus> statuses);

    /** 목록 — 전역 주체는 전부, 팀 사용자는 자기 요청과 자기 팀 범위 작업. visible()과 같은 규칙을 쿼리로 옮긴 것이다 */
    @Query("""
            select j from AiOperationJob j
            where :everyone = true or j.requester = :name or j.scopeTeam = :team
            order by j.requestedAt desc
            """)
    List<AiOperationJob> findVisible(@Param("everyone") boolean everyone, @Param("name") String name,
                                     @Param("team") String team, Pageable page);

    /** 리퍼 — 실행기가 선점한 뒤 리스를 넘기도록 소식이 없는 작업 */
    List<AiOperationJob> findByStatusInAndLeaseUntilBefore(Collection<AiOperationStatus> statuses, OffsetDateTime now);

    /** 리퍼 — 접수된 뒤 어떤 실행기도 가져가지 않은 작업 */
    List<AiOperationJob> findByStatusAndUpdatedAtBefore(AiOperationStatus status, OffsetDateTime before);

    /** 상태별 작업 수 — 게이지 갱신용. 전체 행을 읽지 않고 DB에서 센다 */
    @Query("select j.status, count(j) from AiOperationJob j where j.status in :statuses group by j.status")
    List<Object[]> countByStatuses(@Param("statuses") Collection<AiOperationStatus> statuses);

    /** 진행 중 작업이 마지막으로 진전한 시각 중 가장 오래된 것 — 큐·실행기가 멈추면 이 값이 자란다 */
    @Query("select min(j.updatedAt) from AiOperationJob j where j.status in :statuses")
    OffsetDateTime oldestProgressAt(@Param("statuses") Collection<AiOperationStatus> statuses);
}
