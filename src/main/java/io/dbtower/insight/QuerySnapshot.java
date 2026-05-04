package io.dbtower.insight;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 특정 시각에 수집한 쿼리별 누적 통계 한 줄.
 * 같은 capturedAt을 가진 행들이 하나의 수집 배치를 이룬다.
 * 시점 비교는 "구간 양 끝 배치의 카운터 차분"으로 계산한다.
 */
@Entity
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

    protected QuerySnapshot() {
    }

    public QuerySnapshot(Long instanceId, LocalDateTime capturedAt, String queryId,
                         String queryText, long calls, double totalTimeMs, long rowsExamined) {
        this.instanceId = instanceId;
        this.capturedAt = capturedAt;
        this.queryId = queryId;
        this.queryText = queryText;
        this.calls = calls;
        this.totalTimeMs = totalTimeMs;
        this.rowsExamined = rowsExamined;
    }

    public Long getInstanceId() { return instanceId; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public String getQueryId() { return queryId; }
    public String getQueryText() { return queryText; }
    public long getCalls() { return calls; }
    public double getTotalTimeMs() { return totalTimeMs; }
    public long getRowsExamined() { return rowsExamined; }
}
