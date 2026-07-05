package io.dbtower.slo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 헬스 샘플 저장소 (Phase D4). 가용성 SLI·에러 버짓은 여기서 뽑은 윈도우 카운트로 계산한다.
 * 개별 행을 로딩하지 않고 count 집계만 내려 가볍게 유지한다(윈도우가 길어도 메모리 부담 없음).
 */
public interface HealthSampleRepository extends JpaRepository<HealthSample, Long> {

    /** 윈도우 [from, now] 안의 전체 샘플 수 */
    long countByInstanceIdAndSampledAtAfter(Long instanceId, LocalDateTime from);

    /** 윈도우 [from, now] 안의 up(또는 down) 샘플 수 */
    long countByInstanceIdAndUpAndSampledAtAfter(Long instanceId, boolean up, LocalDateTime from);

    /** 보존 기간이 지난 샘플 일괄 삭제 — 반환값은 삭제된 행 수(벌크 DELETE, 컨텍스트 우회) */
    @Modifying(clearAutomatically = true)
    @Query("delete from HealthSample h where h.sampledAt < :cutoff")
    int deleteBySampledAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
