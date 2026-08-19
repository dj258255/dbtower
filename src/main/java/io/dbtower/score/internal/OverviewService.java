package io.dbtower.score.internal;

import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.dbtower.score.HealthScoreView;
import io.dbtower.score.InstanceOverview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 대상별 운영 종합(overview) 조립 — 흩어진 per-instance 신호(헬스 스코어·복제·백업)를 한 객체로 모은다.
 * score 모듈에 두는 이유: 이 모듈이 이미 advisor·backup·insight·registry·slo를 참조하는 집계 허브라
 * 여기에 복제(operator)만 더하면 순환 없이 종합이 완성된다.
 *
 * <p>대상 조회(복제·백업)는 대상이 죽어 있으면 예외를 던질 수 있다 — 한 조각이 죽어도 나머지는 채워
 * 돌려준다(부분 실패를 사각지대로 감추지 않고 사유를 실어 표기).
 */
@Service
public class OverviewService {

    private static final Logger log = LoggerFactory.getLogger(OverviewService.class);

    private final RegistryService registry;
    private final ScoreService scoreService;
    private final BackupFreshnessService backupFreshness;
    private final DbmsOperatorFactory operatorFactory;

    public OverviewService(RegistryService registry, ScoreService scoreService,
                           BackupFreshnessService backupFreshness, DbmsOperatorFactory operatorFactory) {
        this.registry = registry;
        this.scoreService = scoreService;
        this.backupFreshness = backupFreshness;
        this.operatorFactory = operatorFactory;
    }

    public InstanceOverview overviewFor(Long id) {
        DatabaseInstance i = registry.findById(id);   // 스코프 밖이면 404
        HealthScoreView h = scoreService.scoreFor(id);
        return new InstanceOverview(
                i.getId(), i.getName(), i.getType(), i.getHost(), i.getPort(),
                i.getEnvironment(), i.getClusterLabel(), i.getTeamLabel(),
                h.score(), h.grade(), h.down(),
                replicationSummary(i), backupSummary(i));
    }

    private InstanceOverview.Replication replicationSummary(DatabaseInstance i) {
        try {
            ReplicationState s = operatorFactory.create(i).replicationState();
            if (s == null) {
                return null;
            }
            return new InstanceOverview.Replication(
                    s.role(), s.lagSeconds(), s.lagSource().name(), s.detail(), rpoExposure(s));
        } catch (Exception e) {
            // 대상 접속 실패 등 — 조각 하나가 죽어도 종합은 돌려주되, 못 읽었다는 사실을 남긴다.
            log.warn("overview 복제 조회 실패 instance={} cause={}", i.getName(), e.getMessage());
            return new InstanceOverview.Replication(null, null,
                    ReplicationState.LagSource.UNAVAILABLE.name(), "복제 상태 조회 실패: " + e.getMessage(), null);
        }
    }

    /**
     * RPO 노출 = 지금 failover하면 잃을 데이터. 복제 지연이 실측(MEASURED)일 때만 의미가 있다 —
     * 그 외(NOT_APPLICABLE/UNSUPPORTED/UNAVAILABLE)에서는 지연 자체를 모르므로 노출도 알 수 없다(null).
     */
    private String rpoExposure(ReplicationState s) {
        if (s.lagSource() != ReplicationState.LagSource.MEASURED || s.lagSeconds() == null) {
            return null;
        }
        double lag = s.lagSeconds();
        return lag <= 0
                ? "0s (따라잡음 — 유실 창 없음)"
                : "약 %.0fs 분량 (마지막으로 복제된 지점 이후)".formatted(lag);
    }

    private InstanceOverview.Backup backupSummary(DatabaseInstance i) {
        try {
            BackupFreshness b = backupFreshness.freshnessFor(i);
            if (b == null) {
                return null;
            }
            return new InstanceOverview.Backup(
                    b.status().name(), b.elapsedHours(), b.verifyStatus(), b.thresholdHours());
        } catch (Exception e) {
            log.warn("overview 백업 신선도 조회 실패 instance={} cause={}", i.getName(), e.getMessage());
            return null;
        }
    }
}
