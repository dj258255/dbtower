package io.dbtower.aiops.internal.persistence;

import io.dbtower.aiops.internal.domain.AiOperationOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AiOperationOutboxRepository extends JpaRepository<AiOperationOutbox, String> {

    /** 선점 후보 — 후보를 읽는 것과 가져가는 것은 다르다. 실제 소유는 {@link #claim}의 갱신 행 수로 정한다. */
    @Query("""
            select o.eventId from AiOperationOutbox o
            where o.status = 'PENDING' or (o.status = 'CLAIMED' and o.claimedUntil < :now)
            order by o.createdAt asc
            """)
    List<String> findClaimable(@Param("now") OffsetDateTime now, Pageable page);

    /** 조건부 선점 — 두 릴레이가 같은 후보를 읽어도 먼저 UPDATE한 쪽만 1행을 얻는다(H2·PostgreSQL 공통 문법). */
    @Modifying(clearAutomatically = true)
    @Query("""
            update AiOperationOutbox o
            set o.status = 'CLAIMED', o.claimToken = :token, o.claimedUntil = :until, o.attempts = o.attempts + 1
            where o.eventId = :eventId
              and (o.status = 'PENDING' or (o.status = 'CLAIMED' and o.claimedUntil < :now))
            """)
    int claim(@Param("eventId") String eventId, @Param("token") String token,
              @Param("now") OffsetDateTime now, @Param("until") OffsetDateTime until);

    /** 발행 완료 — 선점 토큰이 맞을 때만. 리스가 만료돼 다른 릴레이가 다시 가져간 행을 옛 릴레이가 닫지 못한다. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update AiOperationOutbox o
            set o.status = 'PUBLISHED', o.publishedAt = :now, o.claimToken = null, o.claimedUntil = null
            where o.eventId = :eventId and o.status = 'CLAIMED' and o.claimToken = :token
            """)
    int markPublished(@Param("eventId") String eventId, @Param("token") String token,
                      @Param("now") OffsetDateTime now);
}
