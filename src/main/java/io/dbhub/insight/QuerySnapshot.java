package io.dbhub.insight;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 특정 시각에 수집한 쿼리별 누적 통계 한 줄.
 * 같은 capturedAt을 가진 행들이 하나의 수집 배치를 이룬다.
 * 시점 비교는 "구간 양 끝 배치의 카운터 차분"으로 계산한다.
 */
@Entity
@Table(indexes = {
        // 시점 비교 조회의 접근 경로. 인덱스 없이 시작해 풀스캔을 실측한 뒤 추가하는 것이
        // 성능 개선 아크 3번이므로, 최초 커밋에서는 주석으로 남겨둔다.
        // @Index(name = "idx_snapshot_instance_time", columnList = "instanceId, capturedAt")
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
