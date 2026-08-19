package io.dbtower.score;

import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import io.dbtower.score.internal.OverviewService;
import io.dbtower.score.internal.ScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 대상별 종합(overview) 조립 — 흩어진 신호를 한 대상에 모으되, 한 조각(복제)이 죽어도 나머지는
 * 채워 돌려주고 그 사실을 사각지대로 감추지 않는다. RPO 노출은 실측일 때만 실린다(위장 금지).
 */
class OverviewServiceTest {

    private final RegistryService registry = Mockito.mock(RegistryService.class);
    private final ScoreService score = Mockito.mock(ScoreService.class);
    private final BackupFreshnessService backup = Mockito.mock(BackupFreshnessService.class);
    private final DbmsOperatorFactory factory = Mockito.mock(DbmsOperatorFactory.class);
    private final DbmsOperator operator = Mockito.mock(DbmsOperator.class);

    private OverviewService svc;
    private DatabaseInstance instance;

    @BeforeEach
    void setUp() {
        svc = new OverviewService(registry, score, backup, factory);
        instance = Mockito.mock(DatabaseInstance.class);
        when(instance.getId()).thenReturn(1L);
        when(instance.getName()).thenReturn("pg-standby");
        when(instance.getType()).thenReturn(DbmsType.POSTGRESQL);
        when(instance.getHost()).thenReturn("127.0.0.1");
        when(instance.getPort()).thenReturn(5432);
        when(instance.getEnvironment()).thenReturn("prod");
        when(instance.getClusterLabel()).thenReturn("pg-cluster");
        when(instance.getTeamLabel()).thenReturn("payments");
        when(registry.findById(1L)).thenReturn(instance);
        when(score.scoreFor(1L)).thenReturn(new HealthScoreView(1L, 82, "B", false));
        when(factory.create(any())).thenReturn(operator);
        when(backup.freshnessFor(any(DatabaseInstance.class))).thenReturn(new BackupFreshness(
                1L, "pg-standby", DbmsType.POSTGRESQL, null, "VERIFIED", null, 3.5,
                true, BackupFreshness.Status.FRESH, 24));
    }

    @Test
    void 정체_건강_복제_백업을_한_대상에_모은다() {
        when(operator.replicationState()).thenReturn(
                ReplicationState.measured("REPLICA", 3, "recovery 모드"));

        InstanceOverview o = svc.overviewFor(1L);

        assertThat(o.name()).isEqualTo("pg-standby");
        assertThat(o.type()).isEqualTo(DbmsType.POSTGRESQL);
        assertThat(o.cluster()).isEqualTo("pg-cluster");
        assertThat(o.healthScore()).isEqualTo(82);
        assertThat(o.grade()).isEqualTo("B");
        assertThat(o.replication().role()).isEqualTo("REPLICA");
        assertThat(o.replication().lagSource()).isEqualTo("MEASURED");
        assertThat(o.replication().rpoExposure()).contains("3s");
        assertThat(o.backup().status()).isEqualTo("FRESH");
        assertThat(o.backup().elapsedHours()).isEqualTo(3.5);
    }

    @Test
    void 복제_조회가_실패해도_나머지는_채우고_사유를_남긴다() {
        when(operator.replicationState())
                .thenThrow(new RuntimeException("Connection refused"));

        InstanceOverview o = svc.overviewFor(1L);

        // 복제 조각은 못 읽었다는 사실을 UNAVAILABLE + 사유로 남긴다(사각지대로 감추지 않는다)
        assertThat(o.replication().lagSource()).isEqualTo("UNAVAILABLE");
        assertThat(o.replication().detail()).contains("Connection refused");
        // 나머지는 정상적으로 채워진다
        assertThat(o.healthScore()).isEqualTo(82);
        assertThat(o.backup().status()).isEqualTo("FRESH");
    }

    @Test
    void RPO_노출은_실측일_때만_실린다() {
        when(operator.replicationState()).thenReturn(
                ReplicationState.standalone("복제 구성 없음"));

        InstanceOverview o = svc.overviewFor(1L);

        assertThat(o.replication().lagSource()).isEqualTo("NOT_APPLICABLE");
        assertThat(o.replication().rpoExposure()).isNull();
    }
}
