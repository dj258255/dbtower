package io.dbtower.aiops.internal;

import io.dbtower.advisor.AdvisorCheck;
import io.dbtower.advisor.AdvisorFinding;
import io.dbtower.advisor.AdvisorService;
import io.dbtower.advisor.InstanceAdvisorReport;
import io.dbtower.advisor.Severity;
import io.dbtower.aiops.AiOperationTrigger;
import io.dbtower.aiops.AiOperationType;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.finops.FinOpsQuery;
import io.dbtower.finops.WasteSummary;
import io.dbtower.insight.BaselineService;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.QueryDiff;
import io.dbtower.insight.WaitEventHistoryService;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.score.HealthScoreView;
import io.dbtower.score.ScoreQuery;
import io.dbtower.slo.SloReport;
import io.dbtower.slo.SloService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 작업 유형별로 DBTower의 공개 서비스에서 사실과 규칙 판정을 모은다. 모델은 여기서 만든 목록 밖을 보지 못한다.
 *
 * <p>사실은 사람이 읽는 문장이되 수치는 한 가지 표기(소수 첫째 자리, 로케일 무관)로 적는다 — 결과 검증이 소견의
 * 수치를 이 문장들과 글자로 대조하기 때문이다. 표기가 섞이면 맞는 인용도 "검증 안 됨"으로 떨어진다.</p>
 *
 * <p>한 영역의 수집 실패는 그 영역만 불확실성으로 남기고 나머지는 계속 모은다 — score 모듈과 같은 원칙으로,
 * 백업 조회가 실패했다고 쿼리 회귀 사실까지 버리지 않는다.</p>
 */
@Component
class FactCollector {

    enum Section { HEALTH, QUERY, WAITS, ANOMALY, BACKUP, SLO, ADVISOR, COST }

    // 유형이 곧 수집 범위다. 요청 문장으로 범위를 넓히지 않는다 — 문장은 데이터라서 "다른 것도 다 봐줘"가 권한이 되면 안 된다.
    private static final Map<AiOperationType, Set<Section>> SECTIONS = Map.of(
            AiOperationType.QUERY_DIAGNOSIS, EnumSet.of(Section.HEALTH, Section.QUERY, Section.WAITS),
            AiOperationType.REGRESSION_EXPLANATION, EnumSet.of(Section.HEALTH, Section.QUERY, Section.WAITS, Section.ANOMALY),
            AiOperationType.BACKUP_RISK_REVIEW, EnumSet.of(Section.HEALTH, Section.BACKUP),
            AiOperationType.SLO_RISK_REVIEW, EnumSet.of(Section.HEALTH, Section.SLO),
            AiOperationType.ADVISOR_SUMMARY, EnumSet.of(Section.HEALTH, Section.ADVISOR),
            AiOperationType.COST_REVIEW, EnumSet.of(Section.HEALTH, Section.COST),
            AiOperationType.INCIDENT_TRIAGE, EnumSet.of(Section.HEALTH, Section.QUERY, Section.WAITS,
                    Section.ANOMALY, Section.BACKUP, Section.SLO),
            AiOperationType.DB_TEAM_INQUIRY, EnumSet.of(Section.HEALTH, Section.QUERY),
            AiOperationType.PERIODIC_REPORT, EnumSet.of(Section.HEALTH, Section.BACKUP, Section.SLO,
                    Section.ADVISOR, Section.COST));

    /** 정기 리포트가 한 번에 훑는 인스턴스 상한 — 사실 목록이 모델 컨텍스트를 넘치지 않게 */
    static final int REPORT_INSTANCE_CAP = 20;
    private static final int TOP_QUERIES = 5;
    private static final int QUERY_TEXT_CAP = 200;
    // 쿼리 단위 회귀 임계는 alert의 RegressionDetector와 같은 값이다. 경보는 "레이턴시 회귀"라고 했는데 AI 작업의 규칙 판정은
    // 조용하면 같은 구간을 두고 두 기능이 다른 말을 한다. 한쪽을 바꾸면 다른 쪽도 바꾼다(감지기는 internal이라 상수를 공유하지 못한다).
    private static final double LATENCY_REGRESSION_PCT = 200.0;
    private static final double LATENCY_MIN_TARGET_MS = 1.0;
    private static final double QPS_SURGE_PCT = 200.0;
    private static final double QPS_MIN_TARGET = 0.5;
    private static final double ROWS_SURGE_PCT = 500.0;
    private static final double ROWS_MIN_TARGET = 100.0;
    /**
     * 시스템 카탈로그·세션 설정 조회 — 제외하지 않고 표시만 한다. 두 번째 실측에서는 상위 5개가 SET autocommit·@@ 변수 조회였다. 169절 실측에서 부하 상위 5개가 전부 SHOW·performance_schema 조회라
     * "느려진 쿼리"에 업무 쿼리가 한 줄도 없었다. 다이제스트에는 실행 사용자가 없어 누가 날렸는지(DBTower 수집인지)는 단정하지 않는다.
     */
    private static final Pattern CATALOG_QUERY = Pattern.compile(
            "(?is)^\\s*(show\\b|set\\b|select\\s+@@|.*\\b(performance_schema|information_schema|pg_catalog|pg_stat_\\w+|mysql\\.|sys\\.|v\\$\\w+|gv\\$\\w+|dba_\\w+)\\b)");
    /** score 모듈의 F 등급 경계(HealthScore.gradeOf: 60 미만 F) — 이 아래만 규칙 판정에 올린다 */
    private static final int HEALTH_WARN_SCORE = 60;

    record Collected(List<String> facts, List<String> ruleFindings, List<String> uncertainties) {
    }

    private final ScoreQuery scoreQuery;
    private final ComparisonService comparisonService;
    private final WaitEventHistoryService waitEvents;
    private final BaselineService baselineService;
    private final BackupFreshnessService backupFreshness;
    private final SloService sloService;
    private final AdvisorService advisorService;
    private final FinOpsQuery finOps;
    // 경보가 쓴 비교 창. 감지기(RegressionDetector)는 alert의 internal이라 값을 공유하지 못해 같은 설정 키를 읽는다 —
    // 위 임계 상수와 같은 이유다. 한쪽 창만 바꾸면 경보의 수치와 작업의 사실이 다시 갈린다(170절 6번)
    private final int alertRecentMinutes;
    private final int alertBaselineMinutes;

    /** 테스트용 — 감지기 기본 창(최근 5분 대 직전 15분)을 쓴다 */
    FactCollector(ScoreQuery scoreQuery, ComparisonService comparisonService, WaitEventHistoryService waitEvents,
                  BaselineService baselineService, BackupFreshnessService backupFreshness, SloService sloService,
                  AdvisorService advisorService, FinOpsQuery finOps) {
        this(scoreQuery, comparisonService, waitEvents, baselineService, backupFreshness, sloService, advisorService,
                finOps, 5, 15);
    }

    @Autowired
    FactCollector(ScoreQuery scoreQuery, ComparisonService comparisonService, WaitEventHistoryService waitEvents,
                  BaselineService baselineService, BackupFreshnessService backupFreshness, SloService sloService,
                  AdvisorService advisorService, FinOpsQuery finOps,
                  @Value("${dbtower.regression.recent-minutes:5}") int alertRecentMinutes,
                  @Value("${dbtower.regression.baseline-minutes:15}") int alertBaselineMinutes) {
        this.alertRecentMinutes = alertRecentMinutes;
        this.alertBaselineMinutes = alertBaselineMinutes;
        this.scoreQuery = scoreQuery;
        this.comparisonService = comparisonService;
        this.waitEvents = waitEvents;
        this.baselineService = baselineService;
        this.backupFreshness = backupFreshness;
        this.sloService = sloService;
        this.advisorService = advisorService;
        this.finOps = finOps;
    }

    Collected collect(AiOperationType type, List<DatabaseInstance> instances,
                      OffsetDateTime windowFrom, OffsetDateTime windowTo) {
        return collect(type, null, instances, windowFrom, windowTo);
    }

    Collected collect(AiOperationType type, AiOperationTrigger trigger, List<DatabaseInstance> instances,
                      OffsetDateTime windowFrom, OffsetDateTime windowTo) {
        Buffer out = new Buffer();
        // 경보가 만든 회귀 작업만 — 사람이 올린 회귀 질문에는 "경보가 본 창"이 없다
        boolean alertWindow = trigger == AiOperationTrigger.ALERT && type == AiOperationType.REGRESSION_EXPLANATION;
        Set<Section> sections = SECTIONS.get(type);
        boolean multi = instances.size() > 1;
        if (instances.isEmpty()) {
            out.uncertainty("범위 안에 대상 인스턴스가 없어 수집한 사실이 없습니다");
        }
        for (DatabaseInstance instance : instances) {
            String who = "[" + instance.getName() + "]";
            out.fact(who + " 기종 " + instance.getType()
                    + (instance.getEnvironment() == null ? "" : ", 환경 " + instance.getEnvironment())
                    + (instance.getTeamLabel() == null ? "" : ", 팀 " + instance.getTeamLabel()));
            for (Section section : sections) {
                // 정기 리포트는 인스턴스 여럿을 훑으므로 대상 DB를 다시 조회하는 Advisor는 한 대상일 때만 돈다
                if (multi && section == Section.ADVISOR) {
                    continue;
                }
                try {
                    collectSection(section, instance, who, windowFrom, windowTo, out);
                } catch (RuntimeException e) {
                    out.uncertainty(who + " " + label(section) + " 수집 실패: " + brief(e));
                }
            }
            if (alertWindow) {
                try {
                    collectAlertWindow(instance.getId(), who, local(windowTo), out);
                } catch (RuntimeException e) {
                    out.uncertainty(who + " 경보 기준 비교(최근 " + alertRecentMinutes + "분 vs 직전 " + alertBaselineMinutes
                            + "분)를 다시 만들지 못했습니다: " + brief(e));
                }
            }
        }
        return new Collected(List.copyOf(out.facts), List.copyOf(out.rules), List.copyOf(out.uncertainties));
    }

    private void collectSection(Section section, DatabaseInstance instance, String who,
                                OffsetDateTime windowFrom, OffsetDateTime windowTo, Buffer out) {
        long id = instance.getId();
        LocalDateTime to = local(windowTo);
        LocalDateTime from = local(windowFrom);
        switch (section) {
            case HEALTH -> {
                HealthScoreView score = scoreQuery.scoreFor(id);
                out.fact(who + " 헬스 스코어 " + score.score() + "점, 등급 " + score.grade()
                        + (score.down() ? ", 응답 없음(down)" : ""));
                if (score.down()) {
                    out.rule(who + " 인스턴스가 응답하지 않습니다(down)");
                } else if (score.score() < HEALTH_WARN_SCORE) {
                    out.rule(who + " 헬스 스코어 " + score.score() + "점으로 " + HEALTH_WARN_SCORE + "점 미만입니다");
                }
            }
            case QUERY -> collectQueries(id, who, from, to, out);
            case WAITS -> {
                var points = waitEvents.inWindow(id, from, to, TOP_QUERIES);
                if (points.isEmpty()) {
                    out.uncertainty(who + " 분석 구간에 대기 이벤트 이력이 없습니다");
                }
                for (var p : points) {
                    // 횟수는 있는데 시간이 정확히 0이면 0ms가 아니라 시간을 재지 않은 계측기다. MySQL wait/io/socket 계열은 기본
                    // TIMED=NO라 횟수만 센다(169절 실측: client_connection 239710회에 0.0ms). 0ms로 넘기면 모델이 "대기 기여 없음"으로 읽는다
                    String time = p.totalCount() > 0 && p.totalMs() == 0.0
                            ? "시간 기록 없음(횟수만 집계됨)" : "누적 " + num(p.totalMs()) + "ms";
                    out.fact(who + " 대기 이벤트 " + p.category() + "/" + p.event() + " 발생 " + p.totalCount()
                            + "회, " + time);
                }
            }
            case ANOMALY -> {
                var scan = baselineService.detectAnomalies(id, to);
                out.fact(who + " 기준선 이상 탐지: 이상 쿼리 " + scan.anomalies().size() + "개, 학습 중 쿼리 "
                        + scan.learningCount() + "개 (z 임계 " + num(scan.zThreshold()) + ")");
                if (!scan.anomalies().isEmpty()) {
                    out.rule(who + " 같은 요일·시간대 기준선을 벗어난 쿼리가 " + scan.anomalies().size() + "개 있습니다");
                }
            }
            case BACKUP -> collectBackup(backupFreshness.freshnessFor(id), who, out);
            case SLO -> collectSlo(sloService.evaluate(id), who, out);
            case ADVISOR -> collectAdvisor(advisorService.inspect(id), who, out);
            case COST -> {
                WasteSummary waste = finOps.wasteSummary(id);
                if (!waste.supported()) {
                    out.uncertainty(who + " 이 기종은 낭비 신호 분석을 지원하지 않습니다");
                    return;
                }
                out.fact(who + " 낭비 신호 후보 " + waste.candidateCount() + "개");
                if (waste.candidateCount() > 0) {
                    out.rule(who + " 미사용·중복 인덱스 등 낭비 신호 후보가 " + waste.candidateCount() + "개 있습니다");
                }
            }
        }
    }

    private void collectQueries(long id, String who, LocalDateTime from, LocalDateTime to, Buffer out) {
        // 비교 기준은 바로 앞의 같은 길이 구간이다 — 요일·시간대 기준선은 ANOMALY가 따로 본다
        Duration length = Duration.between(from, to);
        ComparisonService.CompareResult result;
        try {
            result = comparisonService.compare(id, from.minus(length), from, from, to);
        } catch (IllegalArgumentException baseMissing) {
            collectWindowOnly(id, who, from, to, baseMissing, out);
            return;
        }
        var base = result.base();
        var target = result.target();
        out.fact(who + " 이전 구간 호출 " + base.totalCalls() + "회, 평균 " + num(base.avgLatencyMs())
                + "ms / 분석 구간 호출 " + target.totalCalls() + "회, 평균 " + num(target.avgLatencyMs()) + "ms");
        if (target.totalCalls() == 0) {
            out.uncertainty(who + " 분석 구간에 수집된 쿼리 통계가 없습니다(수집 정지 또는 유휴)");
            return;
        }
        if (result.avgLatencyChangePct() != null) {
            out.fact(who + " 전체 평균 지연 변화율 " + num(result.avgLatencyChangePct()) + "%");
        }
        if (result.newQueryCount() > 0) {
            // 사실에는 상위 몇 개만 실린다 — 개수만 말하면 모델이 "새 쿼리 표시는 하나뿐인데 2개라니" 하고 모순으로 읽었다(169절)
            out.rule(who + " 이전 구간에 없던 쿼리가 " + result.newQueryCount() + "개 나타났습니다(사실에는 부하 상위 "
                    + TOP_QUERIES + "개만 싣습니다)");
        }
        for (QueryDiff q : result.queries()) {
            if (q.latencyChangePct() != null && q.latencyChangePct() >= LATENCY_REGRESSION_PCT
                    && q.targetAvgMs() >= LATENCY_MIN_TARGET_MS) {
                out.rule(who + " 쿼리 " + q.queryId() + " 평균 지연 " + num(q.latencyChangePct()) + "% 증가(레이턴시 회귀 임계 "
                        + num(LATENCY_REGRESSION_PCT) + "%)");
            }
            if (q.qpsChangePct() != null && q.qpsChangePct() >= QPS_SURGE_PCT && q.targetQps() >= QPS_MIN_TARGET) {
                out.rule(who + " 쿼리 " + q.queryId() + " 초당 호출 " + num(q.qpsChangePct()) + "% 증가(호출량 급증 임계 "
                        + num(QPS_SURGE_PCT) + "%)");
            }
            if (q.rowsPerCallChangePct() != null && q.rowsPerCallChangePct() >= ROWS_SURGE_PCT
                    && q.targetRowsPerCall() >= ROWS_MIN_TARGET) {
                out.rule(who + " 쿼리 " + q.queryId() + " 호출당 행 " + num(q.rowsPerCallChangePct()) + "% 증가(행 폭증 임계 "
                        + num(ROWS_SURGE_PCT) + "%)");
            }
        }
        renderTopQueries(who, result, out);
    }

    /**
     * 경보가 본 창 그대로 다시 비교한다 — 감지기는 "최근 5분 vs 직전 15분"으로 재고, 작업의 쿼리 비교는 분석 구간(20분)
     * 전체를 앞의 같은 길이와 비교한다. 170절에서 같은 쿼리가 경보에서는 QPS 0.35, 사실에서는 0.1로 갈려 모델이
     * "경보의 수치를 근거로 쓰지 않았다"고 했다. 경보 문장을 요청 본문으로 받아 사실로 옮기지 않는 이유는, 그러면
     * 자동화 주체가 사실을 만들어 넣을 수 있기 때문이다 — 같은 비교를 플랫폼 데이터로 다시 만든다.
     *
     * <p>회귀 규칙 판정은 다시 내지 않는다. 그 판정은 경보가 이미 냈고, 창이 다른 판정이 둘 서면 모순으로 읽힌다.</p>
     */
    private void collectAlertWindow(long id, String who, LocalDateTime to, Buffer out) {
        LocalDateTime recentFrom = to.minusMinutes(alertRecentMinutes);
        LocalDateTime baseFrom = recentFrom.minusMinutes(alertBaselineMinutes);
        ComparisonService.CompareResult result = comparisonService.compare(id, baseFrom, recentFrom, recentFrom, to);
        String label = who + " [경보 기준: 최근 " + alertRecentMinutes + "분 vs 직전 " + alertBaselineMinutes + "분]";
        out.fact(label + " 직전 구간 호출 " + result.base().totalCalls() + "회, 평균 " + num(result.base().avgLatencyMs())
                + "ms / 최근 구간 호출 " + result.target().totalCalls() + "회, 평균 " + num(result.target().avgLatencyMs()) + "ms");
        renderTopQueries(label, result, out);
    }

    /** 부하 상위 쿼리를 사실로 적는다. 순서: 업무 쿼리 먼저, 그 안에서 새 쿼리 먼저, 그다음 부하 증가량 */
    private static void renderTopQueries(String who, ComparisonService.CompareResult result, Buffer out) {
        // 새 쿼리는 부하가 작아도 첫 후보인데 부하 순으로만 자르자 규칙이 "새 쿼리 2개"를 말하면서 사실에는 그 쿼리가 없었다(169절)
        List<QueryDiff> top = result.queries().stream()
                .sorted(Comparator.comparing(FactCollector::catalog)
                        .thenComparing(QueryDiff::newQuery, Comparator.reverseOrder())
                        .thenComparing(Comparator.comparingDouble(FactCollector::loadDelta).reversed()))
                .limit(TOP_QUERIES)
                .toList();
        for (QueryDiff q : top) {
            String text = maskedText(q);
            // 새 쿼리의 이전 값 0은 측정값이 아니라 "없음"이다 — "0.0ms -> 1.9ms"로 적으면 지연이 생긴 것처럼 읽힌다
            String change = q.newQuery()
                    ? " (새 쿼리): 평균 " + num(q.targetAvgMs()) + "ms, 초당 " + num(q.targetQps())
                            + ", 호출당 행 " + num(q.targetRowsPerCall())
                    : ": 평균 " + num(q.baseAvgMs()) + "ms -> " + num(q.targetAvgMs()) + "ms"
                            + ", 초당 " + num(q.baseQps()) + " -> " + num(q.targetQps())
                            + ", 호출당 행 " + num(q.baseRowsPerCall()) + " -> " + num(q.targetRowsPerCall());
            out.fact(who + " 쿼리 " + q.queryId() + (catalog(q) ? " [시스템·세션 조회]" : "") + change + " | " + text);
        }
    }

    /**
     * 이전 구간 스냅샷이 없을 때 분석 구간만 본다. 실측(169절)에서 앱을 막 띄운 환경은 이전 1시간이 비어 비교가 통째로 실패했고,
     * "느려진 쿼리 봐줘"에 쿼리 이야기를 한 줄도 못 했다. 같은 구간을 양쪽에 넣으면 그 구간의 통계가 나온다 —
     * 대신 변화율(항상 0%)과 회귀 규칙 판정은 만들지 않는다. "느려졌다"는 비교 없이는 말할 수 없는 사실이다.
     */
    private void collectWindowOnly(long id, String who, LocalDateTime from, LocalDateTime to,
                                   IllegalArgumentException baseMissing, Buffer out) {
        ComparisonService.CompareResult self;
        // 분석 구간도 비었으면 여기서 예외가 그대로 올라가 수집 실패(불확실성)로 드러난다
        self = comparisonService.compare(id, from, to, from, to);
        out.uncertainty(who + " 이전 구간 스냅샷이 부족해 변화율을 계산하지 못했습니다. 분석 구간 상위 쿼리만 제공합니다 ("
                + baseMissing.getMessage() + ")");
        var target = self.target();
        out.fact(who + " 분석 구간 호출 " + target.totalCalls() + "회, 평균 " + num(target.avgLatencyMs())
                + "ms (이전 구간 비교 없음)");
        self.queries().stream()
                .sorted(Comparator.comparing(FactCollector::catalog)
                        .thenComparing(Comparator.comparingDouble((QueryDiff q) -> q.targetQps() * q.targetAvgMs()).reversed()))
                .limit(TOP_QUERIES)
                .forEach(q -> out.fact(who + " 쿼리 " + q.queryId() + (catalog(q) ? " [시스템·세션 조회]" : "")
                        + ": 평균 " + num(q.targetAvgMs()) + "ms"
                        + ", 초당 " + num(q.targetQps()) + ", 호출당 행 " + num(q.targetRowsPerCall())
                        + " | " + maskedText(q)));
    }

    private static String maskedText(QueryDiff q) {
        // 리터럴은 항상 가린다 — 사실은 Slack 채널에도 실리고, 채널 구성원이 워크벤치 권한을 가졌다는 보장이 없다
        String text = QueryMasker.maskLiterals(q.queryText() == null ? "" : q.queryText());
        return text.length() > QUERY_TEXT_CAP ? text.substring(0, QUERY_TEXT_CAP) + "..." : text;
    }

    /** 업무 쿼리를 앞에 세우려고 정렬 키로 쓴다(false가 먼저) */
    static boolean catalog(QueryDiff q) {
        return q.queryText() != null && CATALOG_QUERY.matcher(q.queryText()).find();
    }

    /** 부하 증가량(초당 호출 x 평균 지연) — 지연 변화율만으로 고르면 1회 호출된 쿼리가 맨 위로 온다 */
    private static double loadDelta(QueryDiff q) {
        return q.targetQps() * q.targetAvgMs() - q.baseQps() * q.baseAvgMs();
    }

    private static void collectBackup(BackupFreshness b, String who, Buffer out) {
        out.fact(who + " 백업 상태 " + b.status()
                + (b.elapsedHours() == null ? "" : ", 마지막 성공 백업 후 " + num(b.elapsedHours()) + "시간")
                + ", 신선 임계 " + b.thresholdHours() + "시간"
                + ", 복원 검증 " + (b.verifyStatus() == null ? "기록 없음" : b.verifyStatus())
                + ", 원격 보관 " + (b.remoteLocation() == null ? "없음" : "있음"));
        switch (b.status()) {
            case NO_BACKUP -> out.rule(who + " 성공한 백업 이력이 없습니다");
            case STALE -> out.rule(who + " 마지막 백업이 신선 임계 " + b.thresholdHours() + "시간을 넘었습니다");
            case FRESH -> { }
        }
        if (b.status() != BackupFreshness.Status.NO_BACKUP && !"VERIFIED".equals(b.verifyStatus())) {
            out.rule(who + " 마지막 백업의 복원이 검증되지 않았습니다");
        }
        if (b.status() != BackupFreshness.Status.NO_BACKUP && b.remoteLocation() == null) {
            out.rule(who + " 백업이 원격에 보관되지 않았습니다(로컬만)");
        }
    }

    private static void collectSlo(SloReport slo, String who, Buffer out) {
        var latency = slo.latency();
        var availability = slo.availability();
        var budget = slo.errorBudget();
        out.fact(who + " SLO 판정 " + slo.verdict()
                + ", 지연 " + (latency.observedMs() == null ? "표본 없음" : num(latency.observedMs()) + "ms")
                + " (임계 " + num(latency.thresholdMs()) + "ms, " + latency.verdict() + ")"
                + ", 가용성 " + (availability.upRatio() == null ? "표본 없음" : num(availability.upRatio() * 100) + "%")
                + " (목표 " + num(availability.targetRatio() * 100) + "%, " + availability.verdict() + ")");
        if (budget.budgetRemainingRatio() != null) {
            out.fact(who + " 에러 버짓 잔여 " + num(budget.budgetRemainingRatio() * 100) + "%, 판정 " + budget.verdict()
                    + (budget.burnRate() == null ? "" : ", 번인 레이트 " + num(budget.burnRate())));
        }
        if (SloReport.BREACHING.equals(slo.verdict())) {
            out.rule(who + " SLO를 위반하고 있습니다");
        } else if (SloReport.AT_RISK.equals(slo.verdict())) {
            out.rule(who + " 에러 버짓 소진이 임박했습니다");
        } else if (SloReport.INSUFFICIENT_DATA.equals(slo.verdict())) {
            out.uncertainty(who + " SLO 판정에 필요한 표본이 부족합니다");
        }
    }

    private static void collectAdvisor(InstanceAdvisorReport report, String who, Buffer out) {
        out.fact(who + " Advisor 지적 CRITICAL " + report.critical() + "개, WARNING " + report.warning()
                + "개, INFO " + report.info() + "개");
        if (report.critical() > 0) {
            out.rule(who + " CRITICAL Advisor 지적이 " + report.critical() + "개 있습니다");
        }
        for (AdvisorCheck check : report.checks()) {
            if (check.status() == AdvisorCheck.Status.ERROR) {
                out.uncertainty(who + " Advisor " + check.title() + " 점검 실패");
            }
            for (AdvisorFinding f : check.findings()) {
                if (f.severity() != Severity.INFO) {
                    out.fact(who + " [" + f.severity() + "] " + f.title() + " - " + f.detail()
                            + " / 권고: " + f.recommendation());
                }
            }
        }
    }

    /**
     * 수치 표기. 1보다 작은 0이 아닌 값은 유효 자리를 남긴다 — 소수 첫째 자리로 반올림하자 초당 0.04회가 "초당 0.0"이 되어
     * 호출이 없는 쿼리처럼 읽혔다(169절). 결과 검증은 표기 차이(끝자리 0)를 지운 정규형으로 대조하므로 자릿수가 섞여도 된다.
     */
    static String num(double value) {
        if (value != 0.0 && Math.abs(value) < 1.0) {
            String s = String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "");
            return s.endsWith(".") ? s + "0" : s;
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static LocalDateTime local(OffsetDateTime t) {
        return t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static String brief(RuntimeException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    private static String label(Section s) {
        return switch (s) {
            case HEALTH -> "헬스 스코어";
            case QUERY -> "쿼리 비교";
            case WAITS -> "대기 이벤트";
            case ANOMALY -> "기준선 이상";
            case BACKUP -> "백업 신선도";
            case SLO -> "SLO";
            case ADVISOR -> "Advisor";
            case COST -> "낭비 신호";
        };
    }

    private static final class Buffer {
        final List<String> facts = new ArrayList<>();
        final List<String> rules = new ArrayList<>();
        final List<String> uncertainties = new ArrayList<>();

        void fact(String s) { facts.add(s); }
        void rule(String s) { rules.add(s); }
        void uncertainty(String s) { uncertainties.add(s); }
    }
}
