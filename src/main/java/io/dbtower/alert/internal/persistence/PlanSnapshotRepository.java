package io.dbtower.alert.internal.persistence;

import io.dbtower.alert.internal.domain.PlanSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlanSnapshotRepository extends JpaRepository<PlanSnapshot, Long> {

    Optional<PlanSnapshot> findTopByInstanceIdAndQueryIdOrderByCapturedAtDesc(Long instanceId, String queryId);

    List<PlanSnapshot> findTop50ByInstanceIdOrderByCapturedAtDesc(Long instanceId);

    /**
     * 보존 정리(B-2) — (instance_id, query_id)별로 최신 keep개만 남기고 나머지를 지운다. 반환값은 삭제 행 수.
     *
     * <p>plan_snapshot은 회귀가 감지된 쿼리의 계획 변경 때만 append되지만 정리 잡이 없어 무한 성장이었다.
     * "최신 N개 유지"는 (요일×시간대 같은) 시간 기준보다 "쿼리별로 변경 이력 몇 세대"를 직관적으로 보존한다.
     *
     * <p>JPQL은 윈도우 함수를 못 쓰므로 네이티브로 둔다 — {@code ROW_NUMBER() OVER (PARTITION BY ...)}는
     * PostgreSQL(운영)과 H2 PostgreSQL 모드(테스트) 모두 지원한다. 정렬 tie-break로 id DESC를 넣어
     * captured_at이 같아도 결정적으로 최신을 고른다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM plan_snapshot WHERE id IN (
              SELECT id FROM (
                SELECT id, ROW_NUMBER() OVER (
                  PARTITION BY instance_id, query_id ORDER BY captured_at DESC, id DESC) AS rn
                FROM plan_snapshot
              ) ranked WHERE rn > :keep
            )""", nativeQuery = true)
    int deleteExceedingPerQuery(@Param("keep") int keep);
}
