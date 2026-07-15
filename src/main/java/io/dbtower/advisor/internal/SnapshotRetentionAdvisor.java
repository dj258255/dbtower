package io.dbtower.advisor.internal;

import io.dbtower.advisor.Advisor;
import io.dbtower.advisor.AdvisorFinding;
import io.dbtower.advisor.Severity;

import io.dbtower.insight.QuerySnapshotRepository;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 스냅샷 보존 미설정 점검 (Phase D2) — DBTower 자신의 메타 DB 위생을 본다.
 *
 * SnapshotScheduler는 인스턴스당 60초마다 최대 100행을 쌓는다. dbtower.snapshot.retention-days가
 * 0 이하이면 SnapshotRetentionJob이 정리를 건너뛰어 무한 적재가 된다 — 진단 플랫폼의 메타 DB가
 * 관리 대상보다 먼저 포화되는 구조적 한계(SnapshotRetentionJob 주석). 이 Advisor는 그 설정이 꺼진
 * 상태에서 해당 인스턴스에 실제로 스냅샷이 누적되고 있으면(증거) 경고한다.
 *
 * 대상은 DBTower 메타 DB라 기종 무관 — 5기종 모두 지원한다. 이 Advisor만 operator를 쓰지 않고
 * 메타 DB(QuerySnapshotRepository)를 읽는다(advisor -> insight 단방향 의존, 순환 없음).
 */
@Component
public class SnapshotRetentionAdvisor implements Advisor {

    private final QuerySnapshotRepository snapshotRepository;
    private final int retentionDays;

    public SnapshotRetentionAdvisor(QuerySnapshotRepository snapshotRepository,
                                    @Value("${dbtower.snapshot.retention-days:7}") int retentionDays) {
        this.snapshotRepository = snapshotRepository;
        this.retentionDays = retentionDays;
    }

    @Override
    public String id() {
        return "snapshot-retention";
    }

    @Override
    public String title() {
        return "스냅샷 보존 미설정(메타 DB 무한 적재)";
    }

    @Override
    public boolean supports(DbmsType type) {
        return true; // 대상 DB가 아니라 DBTower 메타 DB 위생이라 기종 무관
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        if (retentionDays > 0) {
            return List.of(); // 보존 정책이 켜져 있으면 무한 적재가 아니다
        }
        // 증거: 이 인스턴스의 스냅샷 배치가 실제로 쌓여 있는가. 매우 오래된 시점부터 현재까지 조회해
        // 배치 존재를 확인한다(sumByBatch는 배치별 집계라 반환 크기가 배치 수로 제한된다).
        long batches = snapshotRepository.sumByBatch(instance.getId(),
                LocalDateTime.now().minusYears(100), LocalDateTime.now()).size();
        if (batches == 0) {
            return List.of(); // 아직 수집된 스냅샷이 없으면 적재 문제가 아니다
        }
        return List.of(new AdvisorFinding(Severity.WARNING,
                "스냅샷 보존이 꺼져 있음(retention-days=" + retentionDays + ")",
                ("보존 정리가 비활성이라 이 인스턴스의 쿼리 스냅샷이 정리 없이 무한 적재된다(현재 %d개 수집 배치). "
                        + "메타 DB가 관리 대상보다 먼저 포화될 수 있다.").formatted(batches),
                "dbtower.snapshot.retention-days를 양수(예: 7)로 설정해 보존 정리를 켠다."));
    }
}
