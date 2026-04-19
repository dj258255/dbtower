package io.dbhub.insight;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface QuerySnapshotRepository extends JpaRepository<QuerySnapshot, Long> {

    List<QuerySnapshot> findByInstanceIdAndCapturedAtBetweenOrderByCapturedAt(
            Long instanceId, LocalDateTime from, LocalDateTime to);

    /** 수집 배치별 누적 카운터 합계 — 웹 UI 활동 그래프용. 인접 배치의 차분이 그 구간의 발생량이다 */
    @Query("""
            select s.capturedAt as capturedAt, sum(s.calls) as totalCalls, sum(s.totalTimeMs) as totalTimeMs
            from QuerySnapshot s
            where s.instanceId = :instanceId and s.capturedAt between :from and :to
            group by s.capturedAt order by s.capturedAt""")
    List<BatchTotal> sumByBatch(@Param("instanceId") Long instanceId,
                                @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    interface BatchTotal {
        LocalDateTime getCapturedAt();
        long getTotalCalls();
        double getTotalTimeMs();
    }
}
