package io.dbtower.workbench.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 대량 일괄 변경의 배치 하나. 스키마의 단일 권위는 V48 마이그레이션이다.
 *
 * <p>이 기록이 "어디까지 적용됐는가"의 유일한 근거다 — 취소·실패가 이미 커밋한 배치를 되돌리지 않으므로,
 * 마지막 키가 재개 지점이자 사람이 백업 복원을 판단할 자리가 된다.
 *
 * <p>키를 문자열로 남기는 이유: 기종·열 타입마다 값이 달라 한 열에 담으려면 문자열이 된다. 이 값은
 * 다음 배치의 하한으로 쓰이지 않는다(그건 실행 중 메모리의 값이다) — 사람이 읽고 판단할 기록이다.
 *
 * <p>{@code lagSource}는 그 배치를 커밋할 때 본 복제 상태의 출처다. "지연 0"과 "못 쟀다"를 같은 값으로
 * 남기지 않는다 — 나중에 이 기록으로 "왜 안 멈췄나"를 되짚을 수 있어야 한다.
 */
@Entity
@Table(name = "bulk_change_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BulkChangeBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reviewId;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false)
    private int batchNo;

    @Column(length = 200)
    private String fromKey;

    @Column(nullable = false, length = 200)
    private String toKey;

    @Column(nullable = false)
    private long affectedRows;

    @Column(nullable = false)
    private long elapsedMillis;

    private Double lagSeconds;

    @Column(length = 20)
    private String lagSource;

    @Column(nullable = false)
    private LocalDateTime committedAt;

    public BulkChangeBatch(Long reviewId, Long instanceId, int batchNo, String fromKey, String toKey,
                           long affectedRows, long elapsedMillis, Double lagSeconds, String lagSource,
                           LocalDateTime committedAt) {
        this.reviewId = reviewId;
        this.instanceId = instanceId;
        this.batchNo = batchNo;
        this.fromKey = fromKey;
        this.toKey = toKey;
        this.affectedRows = affectedRows;
        this.elapsedMillis = elapsedMillis;
        this.lagSeconds = lagSeconds;
        this.lagSource = lagSource;
        this.committedAt = committedAt;
    }
}
