package io.dbtower.score.internal;

import io.dbtower.advisor.AdvisorService;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.insight.BaselineService;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.HealthStatus;
import io.dbtower.registry.RegistryService;
import io.dbtower.score.internal.SignalContribution.Signal;
import io.dbtower.slo.SloService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 통합 헬스 스코어 종합 (Phase D8) — 인스턴스별로 다섯 신호를 모아 하나의 점수/등급으로 합산한다.
 *
 * 오직 읽고 종합만 한다(정체성 가드레일): 대상 DB에 직접 붙지 않고, 이미 있는 서비스(registry·insight·
 * advisor·slo·backup)의 반환만 읽어 합산한다. 대상 DB 변경 0.
 *
 * <b>신호 격리:</b> 신호 하나의 수집이 예외로 실패해도(권한·접속·데이터 없음) 나머지 신호로 점수를 계산한다 —
 * 실패한 신호는 ERROR로, 데이터가 부족한 신호는 INSUFFICIENT_DATA로 격리하고 partial(부분 데이터)로 표기한다.
 * 없는 신호를 0점·장애로 오판하지 않는다.
 */
@Service
public class ScoreService {

    private static final Logger log = LoggerFactory.getLogger(ScoreService.class);

    private final RegistryService registryService;
    private final BaselineService baselineService;
    private final AdvisorService advisorService;
    private final SloService sloService;
    private final BackupFreshnessService freshnessService;
    private final ScoreWeights weights;

    public ScoreService(RegistryService registryService, BaselineService baselineService,
                        AdvisorService advisorService, SloService sloService,
                        BackupFreshnessService freshnessService,
                        @Value("${dbtower.score.weights.health-down:45}") double healthDown,
                        @Value("${dbtower.score.weights.anomaly-per-hit:4}") double anomalyPerHit,
                        @Value("${dbtower.score.weights.anomaly-cap:16}") double anomalyCap,
                        @Value("${dbtower.score.weights.advisor-critical:8}") double advisorCritical,
                        @Value("${dbtower.score.weights.advisor-warning:3}") double advisorWarning,
                        @Value("${dbtower.score.weights.advisor-cap:30}") double advisorCap,
                        @Value("${dbtower.score.weights.slo-breaching:25}") double sloBreaching,
                        @Value("${dbtower.score.weights.slo-at-risk:10}") double sloAtRisk,
                        @Value("${dbtower.score.weights.backup-no-backup:20}") double backupNoBackup,
                        @Value("${dbtower.score.weights.backup-stale:12}") double backupStale) {
        this.registryService = registryService;
        this.baselineService = baselineService;
        this.advisorService = advisorService;
        this.sloService = sloService;
        this.freshnessService = freshnessService;
        this.weights = new ScoreWeights(healthDown, anomalyPerHit, anomalyCap,
                advisorCritical, advisorWarning, advisorCap, sloBreaching, sloAtRisk,
                backupNoBackup, backupStale);
    }

    /**
     * 헬스 스코어 캐시 (Phase F, 스케일 제어) — 노드별 인메모리. 스코어 한 번 계산은 인스턴스마다 다섯 신호를
     * 모으는(health 프로브로 대상 DB에 붙는) 무거운 작업이라, 대시보드를 열 때마다 재계산하면 조회가 곧 부하가
     * 된다. 주기 폴러가 미리 계산해 두고 조회는 캐시를 돌려준다. 집계 시각은 리포트가 담아 UI에 표기된다.
     */
    private volatile HealthScoreReport cached;

    /**
     * 스코어를 주기 계산해 캐시에 담는다. ShedLock을 걸지 않는다 — 캐시는 노드별 인메모리라 각 노드가 자기
     * 캐시를 독립적으로 채워야 한다(한 노드만 계산하면 나머지 노드는 캐시가 비어 매 조회마다 즉석 계산하게 됨).
     * 프로브는 읽기 전용이라 노드마다 도는 것은 멱등하고 부하 상한도 refresh 주기로 묶인다.
     */
    @Scheduled(fixedDelayString = "${dbtower.score.refresh-ms:60000}")
    public void refreshCache() {
        try {
            this.cached = computeAll();
        } catch (Exception e) {
            log.warn("헬스 스코어 캐시 갱신 실패(직전 캐시 유지): {}", e.getMessage());
        }
    }

    /** 전 인스턴스 스코어 — 웹 카드·GET /api/health-score. 캐시가 있으면 그대로, 없으면(첫 조회) 즉석 계산. */
    public HealthScoreReport reportAll() {
        HealthScoreReport snapshot = cached;
        return snapshot != null ? snapshot : computeAll();
    }

    private HealthScoreReport computeAll() {
        LocalDateTime now = LocalDateTime.now();
        List<HealthScore> scores = registryService.findAll().stream()
                .map(instance -> evaluate(instance, now))
                .toList();
        return HealthScoreReport.of(now, scores);
    }

    /** 인스턴스 하나의 상세 분해 — GET /api/instances/{id}/health-score(존재 검증 포함). */
    public HealthScore evaluate(Long instanceId) {
        return evaluate(registryService.findById(instanceId), LocalDateTime.now());
    }

    /** 인스턴스 하나에 다섯 신호를 모아 스코어를 낸다. 신호마다 개별 격리한다. */
    HealthScore evaluate(DatabaseInstance instance, LocalDateTime now) {
        List<SignalContribution> contributions = new ArrayList<>();
        Long id = instance.getId();

        // health는 특별 취급한다: 프로브가 예외로 실패하면(접속 거부 등) 그건 "데이터 부족"이 아니라 "다운"이다.
        // 다른 신호와 달리 health 실패는 인스턴스가 사용자에게도 닿지 않는다는 가장 치명적인 신호라, ERROR로
        // 물러서지 않고 down으로 감점해 나쁜 순 정렬 최상단에 올린다(오퍼레이터가 DataAccessException만 down으로
        // 잡고 풀 초기화 예외는 흘려보내는 경우까지 여기서 down으로 수렴시킨다).
        contributions.add(SignalContribution.fromHealth(probeHealth(id), weights));
        contributions.add(collect(Signal.ANOMALY,
                () -> SignalContribution.fromAnomaly(baselineService.detectAnomalies(id, now), weights)));
        contributions.add(collect(Signal.ADVISOR,
                () -> SignalContribution.fromAdvisor(advisorService.inspect(instance), weights)));
        contributions.add(collect(Signal.SLO,
                () -> SignalContribution.fromSlo(sloService.evaluate(id), weights)));
        contributions.add(collect(Signal.BACKUP,
                () -> SignalContribution.fromBackup(freshnessService.freshnessFor(instance), weights)));

        return HealthScore.of(id, instance.getName(), instance.getType(), now, contributions);
    }

    /** health 프로브 — 실패는 다운으로 수렴시킨다(접속 불가 = 사용자에게도 불가 = 치명). */
    private HealthStatus probeHealth(Long id) {
        try {
            return registryService.health(id);
        } catch (Exception e) {
            log.warn("헬스 스코어 health 프로브 실패(다운으로 판정) id={} cause={}", id, e.getMessage());
            return HealthStatus.down(e.getMessage());
        }
    }

    /**
     * 신호 하나를 격리해 수집한다 — 예외가 나면 그 신호만 ERROR로 접고 나머지 계산을 살린다.
     * (데이터 부족 자체는 예외가 아니라 각 팩토리가 INSUFFICIENT_DATA로 정상 반환한다.)
     */
    private SignalContribution collect(Signal signal, Supplier<SignalContribution> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("헬스 스코어 신호 수집 실패 signal={} cause={}", signal, e.getMessage());
            return SignalContribution.error(signal, e.getMessage());
        }
    }
}
