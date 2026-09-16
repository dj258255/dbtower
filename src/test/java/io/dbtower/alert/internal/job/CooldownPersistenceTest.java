package io.dbtower.alert.internal.job;

import io.dbtower.alert.internal.PlanChangeTracker;
import io.dbtower.alert.internal.WebhookNotifier;
import io.dbtower.alert.internal.persistence.CooldownStore;
import io.dbtower.alert.internal.persistence.JdbcCooldownStore;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.QueryDiff;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.RowsMetric;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 경보 쿨다운이 프로세스 밖으로 나갔는지 — 170절 4번. 재기동 직후 같은 신규 쿼리 경보가 다시 나고 AI 작업까지 다시 만들어졌다.
 *
 * <p>감지 규칙 자체는 RegressionDetectorTest가 인메모리 기본값으로 본다. 여기서는 "같은 저장소를 보는 새 인스턴스"가
 * 재기동한 감지기라는 가정으로, 쿨다운이 인스턴스를 넘어 이어지는지와 저장소 SQL이 실제로 도는지를 본다.</p>
 */
class CooldownPersistenceTest {

    private final RegistryService registry = mock(RegistryService.class);
    private final ComparisonService comparison = mock(ComparisonService.class);
    private final WebhookNotifier notifier = mock(WebhookNotifier.class);
    private final AiAnalyzer aiAnalyzer = mock(AiAnalyzer.class);
    private final PlanChangeTracker planChanges = mock(PlanChangeTracker.class);
    private final DbmsOperatorFactory operators = mock(DbmsOperatorFactory.class);

    private JdbcCooldownStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        // 컨텍스트 테스트들이 쓰는 dbtower 인메모리 DB와 섞이지 않게 이름을 따로 둔다
        ds.setURL("jdbc:h2:mem:cooldown-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE alert_cooldown (cooldown_key VARCHAR(300) PRIMARY KEY, alerted_at TIMESTAMP NOT NULL)");
        store = new JdbcCooldownStore(jdbc);

        when(notifier.sendEmbed(anyString(), any(), any())).thenReturn(true);
        DbmsOperator operator = mock(DbmsOperator.class);
        when(operators.create(any())).thenReturn(operator);
        when(operator.rowsMetric()).thenReturn(RowsMetric.EXAMINED_ROWS);
        when(registry.findAll()).thenReturn(List.of(
                new DatabaseInstance("live-mysql-team-a", DbmsType.MYSQL, "127.0.0.1", 13306, "sample", "u", "p")));
        when(aiAnalyzer.analyze(any(), anyString())).thenReturn(Optional.empty());
        when(planChanges.check(any(), any(), any())).thenReturn(Optional.empty());
        // 170절에서 주입한 자기조인 — 신규 쿼리 유입(QPS 0.35, rows/call 4000)
        var summary = new ComparisonService.WindowSummary(0, 0, 0, 0, 1);
        when(comparison.compare(any(), any(), any(), any(), any())).thenReturn(new ComparisonService.CompareResult(
                summary, summary, null, null, null, 1,
                List.of(new QueryDiff("4be7af9b", "SELECT COUNT(*) FROM sample.orders o1 JOIN sample.orders o2 ON o1.amount = o2.amount",
                        0, 0.35, null, 0, 1.2, null, 0, 4000, null, true))));
    }

    private RegressionDetector detector() {
        return new RegressionDetector(registry, comparison, notifier, aiAnalyzer, new QueryMasker(true, false),
                planChanges, operators, 5, 15, 30, "");
    }

    @Test
    void 같은_저장소를_보는_새_감지기는_재기동_전에_알린_신호를_쿨다운_안에서_다시_알리지_않는다() {
        RegressionDetector before = detector();
        before.setCooldownStore(store);
        before.detect();
        verify(notifier, times(1)).sendEmbed(anyString(), any(), any());

        RegressionDetector afterRestart = detector();
        afterRestart.setCooldownStore(store);
        afterRestart.detect();
        // 재기동 뒤에도 한 번뿐이다 — 예전에는 여기서 두 번째 경보가 나고, 경보가 작업을 만들어 모델 호출도 두 번 났다
        verify(notifier, times(1)).sendEmbed(anyString(), any(), any());

        // 대조군: 저장소를 붙이지 않은(예전처럼 인메모리만 가진) 새 감지기는 다시 알린다 — 이어주는 것이 저장소라는 증거다
        detector().detect();
        verify(notifier, times(2)).sendEmbed(anyString(), any(), any());
    }

    @Test
    void 전송이_실패하면_쿨다운을_기록하지_않아_다음_폴에서_다시_시도한다() {
        when(notifier.sendEmbed(anyString(), any(), any())).thenReturn(false);
        RegressionDetector d = detector();
        d.setCooldownStore(store);
        d.detect();

        // 영속화했다고 "판정 즉시 확정"으로 돌아가면 안 된다 — 웹훅이 잠깐 죽은 순간의 경보가 영구히 사라진다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_cooldown", Integer.class)).isZero();
    }

    @Test
    void 저장소는_덮어쓰고_접두로만_지우며_밑줄을_와일드카드로_읽지_않는다() {
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 16, 6, 34, 49);
        store.record(List.of("regression:2:q_1:new", "regression:2:q1:new", "regression:20:q9:new", "ops:2:instance-down"), t0);
        store.record(List.of("regression:2:q_1:new"), t0.plusMinutes(5));

        assertThat(store.lastAlerted("regression:2:q_1:new")).isEqualTo(t0.plusMinutes(5));
        assertThat(store.lastAlerted("없는-키")).isNull();

        // "regression:2:"는 인스턴스 2의 회귀 키만 — 인스턴스 20과 다른 감지기(ops)의 키는 남는다
        store.evictPrefix("regression:2:");
        assertThat(store.lastAlerted("regression:2:q_1:new")).isNull();
        assertThat(store.lastAlerted("regression:2:q1:new")).isNull();
        assertThat(store.lastAlerted("regression:20:q9:new")).isEqualTo(t0);
        assertThat(store.lastAlerted("ops:2:instance-down")).isEqualTo(t0);

        // LIKE였다면 "q_"의 밑줄이 한 글자 와일드카드라 "qX..."도 지웠을 것이다
        store.record(List.of("anomaly:3:q_a", "anomaly:3:qXa"), t0);
        store.evictPrefix("anomaly:3:q_");
        assertThat(store.lastAlerted("anomaly:3:q_a")).isNull();
        assertThat(store.lastAlerted("anomaly:3:qXa")).isEqualTo(t0);

        store.pruneBefore("regression:", t0.plusMinutes(1));
        assertThat(store.lastAlerted("regression:20:q9:new")).isNull();
        assertThat(store.lastAlerted("ops:2:instance-down")).as("다른 감지기의 키는 남는다").isEqualTo(t0);
    }

    @Test
    void 인메모리_기본값도_같은_계약을_지킨다() {
        CooldownStore mem = CooldownStore.inMemory();
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 16, 6, 34, 49);
        mem.record(List.of("anomaly:3:q_a", "anomaly:3:qXa"), t0);
        mem.evictPrefix("anomaly:3:q_");
        assertThat(mem.lastAlerted("anomaly:3:q_a")).isNull();
        assertThat(mem.lastAlerted("anomaly:3:qXa")).isEqualTo(t0);
    }
}
