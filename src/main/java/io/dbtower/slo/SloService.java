package io.dbtower.slo;

import io.dbtower.slo.internal.persistence.HealthSampleRepository;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.LatencyPercentile;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DB SLO / 에러 버짓 계산 (Phase D4) — Google SRE·DBRE 모델.
 *
 * 레이턴시 SLI는 D4a(operator.latencyPercentiles)를 재사용하고, 백분위 원자료가 없는 기종은 평균 레이턴시로
 * 폴백하되 source로 정직하게 표기한다. 가용성 SLI·에러 버짓은 HealthSample 이력의 윈도우 카운트로 계산한다.
 * 산식은 각 record(ErrorBudget/AvailabilitySli/LatencySli) 주석에 명시했다.
 *
 * 데이터를 모으는 부분(evaluate)과 순수 계산(compute*)을 분리한다 — 산식은 DB 없이 단위 테스트로 고정한다.
 */
@Service
public class SloService {

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final HealthSampleRepository sampleRepository;

    private final double availabilityTarget;
    private final int windowDays;
    private final int burnWindowMinutes;
    private final int minSamples;
    private final double sampleIntervalMinutes;
    private final double latencyThresholdMs;
    private final int latencySampleLimit;

    public SloService(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                      HealthSampleRepository sampleRepository,
                      @Value("${dbtower.slo.availability.target:0.995}") double availabilityTarget,
                      @Value("${dbtower.slo.availability.window-days:30}") int windowDays,
                      @Value("${dbtower.slo.availability.burn-window-minutes:60}") int burnWindowMinutes,
                      @Value("${dbtower.slo.availability.min-samples:5}") int minSamples,
                      @Value("${dbtower.slo.availability.poll-ms:60000}") long pollMs,
                      @Value("${dbtower.slo.latency.p95-threshold-ms:100}") double latencyThresholdMs,
                      @Value("${dbtower.slo.latency.sample-limit:20}") int latencySampleLimit) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.sampleRepository = sampleRepository;
        this.availabilityTarget = availabilityTarget;
        this.windowDays = windowDays;
        this.burnWindowMinutes = burnWindowMinutes;
        this.minSamples = minSamples;
        this.sampleIntervalMinutes = pollMs / 60000.0;
        this.latencyThresholdMs = latencyThresholdMs;
        this.latencySampleLimit = latencySampleLimit;
    }

    /** 인스턴스 하나의 SLO 종합 — 존재하지 않으면 findById가 404를 낸다. */
    public SloReport evaluate(Long instanceId) {
        return evaluate(registryService.findById(instanceId), LocalDateTime.now());
    }

    SloReport evaluate(DatabaseInstance instance, LocalDateTime now) {
        DbmsOperator operator = operatorFactory.create(instance);

        // 레이턴시 SLI — D4a 백분위 우선, UNSUPPORTED면 평균 폴백. 대상 조회 실패 시 데이터 부족으로 정직히 표기.
        LatencySli latency;
        try {
            List<LatencyPercentile> percentiles = operator.latencyPercentiles(latencySampleLimit);
            List<QueryStat> stats = hasPercentileValues(percentiles)
                    ? List.of() // 백분위가 있으면 폴백용 통계는 부르지 않는다
                    : operator.queryStats(latencySampleLimit);
            latency = computeLatency(percentiles, stats);
        } catch (Exception e) {
            latency = new LatencySli(LatencySli.INSUFFICIENT_DATA, null, null, null, latencyThresholdMs,
                    null, null, 0, 0, LatencySli.INSUFFICIENT_DATA,
                    "레이턴시 조회 실패: " + e.getMessage());
        }

        // 가용성 SLI·에러 버짓 — 헬스 샘플 이력 윈도우 카운트로 계산(대상 DB에 붙지 않는다).
        LocalDateTime windowFrom = now.minusDays(windowDays);
        LocalDateTime burnFrom = now.minusMinutes(burnWindowMinutes);
        long total = sampleRepository.countByInstanceIdAndSampledAtAfter(instance.getId(), windowFrom);
        long up = sampleRepository.countByInstanceIdAndUpAndSampledAtAfter(instance.getId(), true, windowFrom);
        long recentTotal = sampleRepository.countByInstanceIdAndSampledAtAfter(instance.getId(), burnFrom);
        long recentUp = sampleRepository.countByInstanceIdAndUpAndSampledAtAfter(instance.getId(), true, burnFrom);

        AvailabilitySli availability = computeAvailability(total, up);
        ErrorBudget budget = computeBudget(total, up, recentTotal, recentUp);

        return new SloReport(instance.getId(), instance.getName(), instance.getType(), now,
                latency, availability, budget, overallVerdict(latency, availability, budget));
    }

    // ---------- 순수 계산(테스트가 직접 고정) ----------

    /** 백분위 원자료가 실제로 있는지 — NATIVE/COMPUTED/ESTIMATED 중 p95 값을 가진 행이 하나라도 있으면 true. */
    static boolean hasPercentileValues(List<LatencyPercentile> percentiles) {
        return percentiles.stream().anyMatch(p ->
                p.p95Ms() != null && !LatencyPercentile.UNSUPPORTED.equals(p.source()));
    }

    /**
     * 레이턴시 SLI — 백분위가 있으면 상위 부하 쿼리들 중 최악 p95를 SLI로(꼬리의 꼬리), 없으면 평균 레이턴시 폴백.
     * source를 절대 섞지 않는다: 백분위 경로는 D4a source(NATIVE/COMPUTED/ESTIMATED) 유지, 폴백은 AVG_FALLBACK.
     */
    LatencySli computeLatency(List<LatencyPercentile> percentiles, List<QueryStat> stats) {
        if (hasPercentileValues(percentiles)) {
            List<LatencyPercentile> valued = percentiles.stream()
                    .filter(p -> p.p95Ms() != null && !LatencyPercentile.UNSUPPORTED.equals(p.source()))
                    .toList();
            LatencyPercentile worst = valued.stream()
                    .max(java.util.Comparator.comparingDouble(LatencyPercentile::p95Ms))
                    .orElseThrow();
            int breaching = (int) valued.stream().filter(p -> p.p95Ms() > latencyThresholdMs).count();
            String verdict = breaching > 0 ? LatencySli.BREACHING : LatencySli.MEETING;
            return new LatencySli(worst.source(), worst.p95Ms(), worst.p95Ms(), worst.p99Ms(),
                    latencyThresholdMs, worst.queryId(), worst.queryText(),
                    breaching, valued.size(), verdict, latencyNote(worst.source()));
        }

        // 폴백 — 백분위 미지원 기종(D4a UNSUPPORTED)이거나 백분위가 비었을 때 평균 레이턴시로.
        long calls = stats.stream().mapToLong(QueryStat::calls).sum();
        if (calls <= 0) {
            return new LatencySli(LatencySli.INSUFFICIENT_DATA, null, null, null, latencyThresholdMs,
                    null, null, 0, 0, LatencySli.INSUFFICIENT_DATA,
                    "쿼리 통계가 없어 레이턴시 SLI를 낼 수 없음(데이터 부족)");
        }
        double totalTime = stats.stream().mapToDouble(QueryStat::totalTimeMs).sum();
        double avg = Math.round(totalTime / calls * 100.0) / 100.0;
        boolean breach = avg > latencyThresholdMs;
        return new LatencySli(LatencySli.AVG_FALLBACK, avg, null, null, latencyThresholdMs,
                null, null, breach ? 1 : 0, 0,
                breach ? LatencySli.BREACHING : LatencySli.MEETING,
                latencyNote(LatencySli.AVG_FALLBACK));
    }

    private static String latencyNote(String source) {
        return switch (source) {
            case LatencyPercentile.NATIVE ->
                    "MySQL QUANTILE 컬럼(실측 p95) — 리셋 이후 누적이라 최근 윈도우 p95는 아님";
            case LatencyPercentile.COMPUTED ->
                    "MongoDB system.profile 원샘플에서 직접 계산한 p95";
            case LatencyPercentile.ESTIMATED ->
                    "PostgreSQL 평균+표준편차 근사(추정) — 꼬리가 무거우면 과소평가 가능, 실측 백분위 아님";
            case LatencySli.AVG_FALLBACK ->
                    "백분위 미지원 기종 — 평균 레이턴시로 폴백. 꼬리(p95)를 못 봐 낙관적일 수 있음";
            default -> "";
        };
    }

    /** 가용성 SLI — 윈도우 up 비율. 표본이 최소치 미만이면 판정 보류(데이터 부족). */
    AvailabilitySli computeAvailability(long total, long up) {
        Double upRatio = total > 0 ? (double) up / total : null;
        if (total < minSamples) {
            return new AvailabilitySli(windowDays, total, up, upRatio, availabilityTarget, minSamples,
                    AvailabilitySli.INSUFFICIENT_DATA,
                    "가용성 이력 부족(%d/%d) — 판정 보류".formatted(total, minSamples));
        }
        String verdict = upRatio >= availabilityTarget ? AvailabilitySli.MEETING : AvailabilitySli.BREACHING;
        return new AvailabilitySli(windowDays, total, up, upRatio, availabilityTarget, minSamples, verdict,
                "윈도우 %d일 · 표본 %d개 중 up %d개".formatted(windowDays, total, up));
    }

    /** 에러 버짓 소진율 + 번인 레이트 — 산식은 ErrorBudget 주석 참고. */
    ErrorBudget computeBudget(long total, long up, long recentTotal, long recentUp) {
        double allowed = (1.0 - availabilityTarget) * windowDays * 24 * 60;
        long down = Math.max(0, total - up);
        double observedDowntime = down * sampleIntervalMinutes;

        if (total < minSamples) {
            return new ErrorBudget(availabilityTarget, windowDays, sampleIntervalMinutes,
                    round2(allowed), round2(observedDowntime), null, null, burnWindowMinutes, null,
                    ErrorBudget.INSUFFICIENT_DATA,
                    "가용성 이력 부족(%d/%d) — 소진율 판정 보류".formatted(total, minSamples));
        }

        Double consumed = allowed > 0 ? round4(observedDowntime / allowed) : null;
        Double remaining = consumed != null ? round4(1.0 - consumed) : null;

        long recentDown = Math.max(0, recentTotal - recentUp);
        Double burnRate = recentTotal > 0
                ? round2(((double) recentDown / recentTotal) / (1.0 - availabilityTarget))
                : null;

        String verdict;
        if (consumed != null && consumed >= 1.0) {
            verdict = ErrorBudget.EXHAUSTED;
        } else if ((burnRate != null && burnRate > 1.0) || (consumed != null && consumed >= 0.8)) {
            verdict = ErrorBudget.WARNING;
        } else {
            verdict = ErrorBudget.OK;
        }

        String note = "허용 다운타임 %.0f분(=(1-%.4f)×%d일) · 관측 %.1f분(down %d샘플×%.1f분) · 번인창 %d분"
                .formatted(allowed, availabilityTarget, windowDays, observedDowntime, down,
                        sampleIntervalMinutes, burnWindowMinutes);
        return new ErrorBudget(availabilityTarget, windowDays, sampleIntervalMinutes,
                round2(allowed), round2(observedDowntime), consumed, remaining, burnWindowMinutes, burnRate,
                verdict, note);
    }

    /** overall 판정 — 위반 우선, 그다음 임박(AT_RISK), 실측 신호가 지키면 MEETING, 둘 다 부족이면 부족. */
    static String overallVerdict(LatencySli latency, AvailabilitySli availability, ErrorBudget budget) {
        boolean breaching = LatencySli.BREACHING.equals(latency.verdict())
                || AvailabilitySli.BREACHING.equals(availability.verdict())
                || ErrorBudget.EXHAUSTED.equals(budget.verdict());
        if (breaching) {
            return SloReport.BREACHING;
        }
        if (ErrorBudget.WARNING.equals(budget.verdict())) {
            return SloReport.AT_RISK;
        }
        boolean anyMeeting = LatencySli.MEETING.equals(latency.verdict())
                || AvailabilitySli.MEETING.equals(availability.verdict());
        return anyMeeting ? SloReport.MEETING : SloReport.INSUFFICIENT_DATA;
    }

    /** 헬스 추이 한 점 — 인시던트 리포트(B4)의 가용성 타임라인 재료. */
    public record HealthPoint(LocalDateTime sampledAt, boolean up, long pingMillis) {
    }

    /**
     * 창 안 헬스 샘플(시간순) — 인시던트 리포트가 "그 구간에 언제 다운됐나"를 붙일 때 쓴다.
     * findById로 스코프를 게이트한 뒤 샘플을 시간순으로 돌려준다(스코프 밖이면 404).
     */
    public List<HealthPoint> healthInWindow(Long instanceId, LocalDateTime from, LocalDateTime to) {
        registryService.findById(instanceId); // LBAC 스코프 게이트
        return sampleRepository.findByInstanceIdAndSampledAtBetweenOrderBySampledAt(instanceId, from, to)
                .stream()
                .map(s -> new HealthPoint(s.getSampledAt(), s.isUp(), s.getPingMillis()))
                .toList();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
