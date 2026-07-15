package io.dbtower.alert.internal.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 실행계획 스냅샷 한 건 — 플랜 변경(plan flip) 감지의 기준선.
 *
 * 계획 원문이 아니라 <b>정규화된 형태(shape)</b>와 그 해시를 남긴다 — 비용·행수 추정치는
 * 데이터가 조금만 변해도 흔들리므로, "옵티마이저가 무엇을 골랐나"(노드 종류·인덱스·대상)만
 * 남겨야 의미 있는 변경만 잡힌다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false)
    private String queryId;

    /** 정규화 shape의 SHA-256 — 변경 판정은 이 값 비교 한 번 */
    @Column(nullable = false, length = 64)
    private String planHash;

    /** 사람이 읽을 정규화 shape (예: "Index Scan(idx_code)>...") — 알림·화면 표시용 */
    @Column(length = 2000)
    private String planShape;

    @Column(nullable = false)
    private LocalDateTime capturedAt;

    public PlanSnapshot(Long instanceId, String queryId, String planHash, String planShape,
                        LocalDateTime capturedAt) {
        this.instanceId = instanceId;
        this.queryId = queryId;
        this.planHash = planHash;
        this.planShape = planShape;
        this.capturedAt = capturedAt;
    }
}
