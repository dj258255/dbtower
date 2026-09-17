package io.dbtower.workbench.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 대량 일괄 변경 한 건의 현재 상태. 스키마의 단일 권위는 V48 마이그레이션이다.
 *
 * <p>왜 메모리가 아니라 표인가: 실행은 한 노드에서 돌지만 진행률 조회는 어느 노드로든 간다(V47 수집 기록과 같은 이유).
 * 더 중요한 것은 재기동이다 — 앱이 실행 중에 죽으면 메모리의 진행은 사라지지만 대상 DB에는 커밋된 배치가 남는다.
 * 이 표가 남아 있어야 "무엇이 중단된 채 남았나"를 사람이 알 수 있다.
 */
@Entity
@Table(name = "bulk_change_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BulkChangeRunRecord {

    @Id
    private Long reviewId;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false, length = 200)
    private String tableName;

    @Column(nullable = false, length = 200)
    private String keyColumn;

    @Column(nullable = false)
    private int batchRows;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(length = 500)
    private String stateReason;

    @Column(length = 200)
    private String lastKey;

    @Column(nullable = false)
    private long affectedRows;

    @Column(nullable = false)
    private int batches;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public BulkChangeRunRecord(Long reviewId, Long instanceId, String tableName, String keyColumn, int batchRows,
                               LocalDateTime startedAt) {
        this.reviewId = reviewId;
        this.instanceId = instanceId;
        this.tableName = tableName;
        this.keyColumn = keyColumn;
        this.batchRows = batchRows;
        this.state = "RUNNING";
        this.startedAt = startedAt;
        this.updatedAt = startedAt;
    }

    /** 진행을 갱신한다. 상태 문자열은 {@code BulkChangeRun.State}의 이름을 그대로 쓴다. */
    public void progress(String state, String reason, String lastKey, long affectedRows, int batches,
                         LocalDateTime now) {
        this.state = state;
        this.stateReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
        this.lastKey = lastKey;
        this.affectedRows = affectedRows;
        this.batches = batches;
        this.updatedAt = now;
    }
}
