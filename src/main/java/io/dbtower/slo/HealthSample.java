package io.dbtower.slo;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 특정 시각에 관측한 인스턴스 가용성 한 점 (Phase D4) — 가용성 SLI의 원자료.
 *
 * 헬스체크(registry.HealthStatus)는 원래 요청 시점에만 계산하고 남기지 않아, "지난 30일 중 몇 %가 up이었나"를
 * 물을 수 없었다. 에러 버짓은 시계열이 있어야 계산되므로, 폴러(SloHealthPoller)가 주기적으로 up/down을 이 표에
 * 남긴다. 다운 샘플 한 개는 대략 수집 주기(poll-ms)만큼의 다운타임을 뜻한다 — 버짓 산식이 이 근사를 쓴다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = {
        // 인스턴스 등치 + 시각 범위 조회(윈도우 집계)용 복합 인덱스 — 등치 컬럼을 선두에.
        @Index(name = "idx_health_sample_instance_time", columnList = "instanceId, sampledAt")
})
public class HealthSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false)
    private LocalDateTime sampledAt;

    @Column(nullable = false)
    private boolean up;

    /** 헬스체크 왕복 시간(ms). down이면 -1 */
    private long pingMillis;

    public HealthSample(Long instanceId, LocalDateTime sampledAt, boolean up, long pingMillis) {
        this.instanceId = instanceId;
        this.sampledAt = sampledAt;
        this.up = up;
        this.pingMillis = pingMillis;
    }
}
