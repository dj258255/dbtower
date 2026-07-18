package io.dbtower.alert.internal.persistence;

import io.dbtower.alert.internal.domain.PlanSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PlanSnapshotRepository extends JpaRepository<PlanSnapshot, Long> {

    Optional<PlanSnapshot> findTopByInstanceIdAndQueryIdOrderByCapturedAtDesc(Long instanceId, String queryId);

    List<PlanSnapshot> findTop50ByInstanceIdOrderByCapturedAtDesc(Long instanceId);

    /**
     * 보존 정리(B-2, D2 시간 하한 병행) — (instance_id, query_id)별 최신 keep개 초과분 중
     * <b>cutoff보다 오래된 행만</b> 지운다. 반환값은 삭제 행 수.
     *
     * <p>plan_snapshot은 회귀가 감지된 쿼리의 계획 변경 때만 append되지만 정리 잡이 없어 무한 성장이었다.
     * "최신 N개 유지"는 쿼리별 변경 이력을 세대로 보존한다. 단 카운트 단독이면 플랜이 자주 뒤집히는
     * 쿼리에서 <b>하루가 닫히기 전에</b> 행이 밀려날 수 있다 — lakehouse가 "어제 하루창"을 추출하는
     * 계약(D+1 이전 유실 금지)과 어긋난다. 그래서 시간 하한(cutoff)을 병행한다: 어린 행은 세대를
     * 초과해도 남기고, cutoff를 넘긴 뒤에야 카운트 규칙이 적용된다. 테이블이 일시적으로 keep개를
     * 넘을 수 있는 것이 의도된 트레이드오프다(추출 정합 > 상한 엄격성).
     *
     * <p>JPQL은 윈도우 함수를 못 쓰므로 네이티브로 둔다 — {@code ROW_NUMBER() OVER (PARTITION BY ...)}는
     * PostgreSQL(운영)과 H2 PostgreSQL 모드(테스트) 모두 지원한다. 정렬 tie-break로 id DESC를 넣어
     * captured_at이 같아도 결정적으로 최신을 고른다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM plan_snapshot WHERE id IN (
              SELECT id FROM (
                SELECT id, captured_at, ROW_NUMBER() OVER (
                  PARTITION BY instance_id, query_id ORDER BY captured_at DESC, id DESC) AS rn
                FROM plan_snapshot
              ) ranked WHERE rn > :keep AND captured_at < :cutoff
            )""", nativeQuery = true)
    int deleteExceedingPerQuery(@Param("keep") int keep, @Param("cutoff") LocalDateTime cutoff);
}
