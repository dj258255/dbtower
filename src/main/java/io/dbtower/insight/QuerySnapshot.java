package io.dbtower.insight;

import io.dbtower.analysis.QueryMasker;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 특정 시각에 수집한 쿼리별 누적 통계 한 줄.
 * 같은 capturedAt을 가진 행들이 하나의 수집 배치를 이룬다.
 * 시점 비교는 "구간 양 끝 배치의 카운터 차분"으로 계산한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = {
        // 개선 아크 3: 인덱스 없이 시작해 DBTower 자신의 explain으로 Seq Scan을 진단한 뒤 추가했다.
        // 50만 행 기준 21.269ms(Parallel Seq Scan) -> 인덱스 후 실측은 VERIFICATION.md 9절.
        // instanceId 등치 + capturedAt 범위 — 등치 컬럼을 선두에 두는 복합 인덱스.
        @Index(name = "idx_snapshot_instance_time", columnList = "instanceId, capturedAt")
})
public class QuerySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false)
    private LocalDateTime capturedAt;

    @Column(nullable = false, length = 64)
    private String queryId;

    @Column(length = 4000)
    private String queryText;

    private long calls;

    private double totalTimeMs;

    private long rowsExamined;

    public QuerySnapshot(Long instanceId, LocalDateTime capturedAt, String queryId,
                         String queryText, long calls, double totalTimeMs, long rowsExamined) {
        this.instanceId = instanceId;
        this.capturedAt = capturedAt;
        this.queryId = queryId;
        // PostgreSQL은 CREATE ROLE 같은 유틸리티 문장의 PASSWORD 리터럴을 pg_stat_statements에 원문으로 남긴다.
        // 통계 저장소가 자격증명 보관소가 되지 않도록 수집 경계에서 모든 리터럴을 정규화한다.
        this.queryText = QueryMasker.maskLiterals(queryText);
        this.calls = calls;
        this.totalTimeMs = totalTimeMs;
        this.rowsExamined = rowsExamined;
    }

    /** 마스킹 도입 전 저장된 행도 API·비교·이상 탐지로 다시 나갈 때 리터럴을 노출하지 않는다. */
    public String getQueryText() {
        return QueryMasker.maskLiterals(queryText);
    }
}
