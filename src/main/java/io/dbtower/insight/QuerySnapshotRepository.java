package io.dbtower.insight;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 보존 기간이 지난 스냅샷 일괄 삭제 — 반환값은 삭제된 행 수.
     *
     * 엔티티 단위 삭제(findBy → deleteAll)는 삭제 대상 전부를 영속성 컨텍스트에
     * 로딩한 뒤 행마다 DELETE를 보낸다 — 하루치만 해도 수십만 행이라
     * 메모리와 왕복 횟수 모두 감당이 안 된다. JPQL 벌크 DELETE는
     * 로딩 없이 DB에서 DELETE 한 문장으로 끝난다.
     *
     * 단, 벌크 쿼리는 영속성 컨텍스트를 우회하므로 이미 로딩돼 있던 엔티티가
     * 삭제된 행을 계속 들고 있는 불일치가 생길 수 있다 — clearAutomatically로
     * 실행 직후 컨텍스트를 비워 낡은 스냅샷 참조를 막는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from QuerySnapshot s where s.capturedAt < :cutoff")
    int deleteByCapturedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
