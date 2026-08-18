package io.dbtower.alert.internal.persistence;

import io.dbtower.alert.internal.domain.AlertHistoryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AlertHistoryRepository extends JpaRepository<AlertHistoryEntry, Long> {

    /** 인시던트 리포트가 "그 구간에 어떤 알림이 나갔나"를 재구성할 때 쓴다. */
    List<AlertHistoryEntry> findByInstanceIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            Long instanceId, LocalDateTime from, LocalDateTime to);

    /** 최근 알림 — 화면·디버깅용. */
    List<AlertHistoryEntry> findTop100ByOrderByOccurredAtDesc();

    /** 보존 정리 — 다른 보존 잡과 같은 벌크 삭제 패턴. */
    @Modifying(clearAutomatically = true)
    @Query("delete from AlertHistoryEntry a where a.occurredAt < :before")
    int deleteByOccurredAtBefore(@Param("before") LocalDateTime before);
}
