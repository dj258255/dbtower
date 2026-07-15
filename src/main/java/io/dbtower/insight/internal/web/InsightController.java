package io.dbtower.insight.internal.web;

import io.dbtower.operator.model.WaitEvent;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.operator.model.ReplicationSlot;
import io.dbtower.operator.model.PartitionInfo;
import io.dbtower.operator.model.LatencyPercentile;
import io.dbtower.operator.model.IndexAdvice;
import io.dbtower.operator.model.DeadlockEvent;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.DeepAnalyzer;
import io.dbtower.analysis.DeepDiagnosis;
import io.dbtower.analysis.RuleBasedAnalyzer;
import io.dbtower.insight.BaselineService;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.QuerySnapshotRepository;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.operator.model.SlowQuery;
import io.dbtower.operator.model.TableStat;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/instances/{id}")
public class InsightController {

    private final RegistryService registryService;
    private final DbmsOperatorFactory operatorFactory;
    private final ComparisonService comparisonService;
    private final RuleBasedAnalyzer analyzer;
    private final AiAnalyzer aiAnalyzer;
    private final DeepAnalyzer deepAnalyzer;
    private final QuerySnapshotRepository snapshotRepository;
    private final BaselineService baselineService;

    public InsightController(RegistryService registryService, DbmsOperatorFactory operatorFactory,
                             ComparisonService comparisonService, RuleBasedAnalyzer analyzer,
                             AiAnalyzer aiAnalyzer, DeepAnalyzer deepAnalyzer,
                             QuerySnapshotRepository snapshotRepository,
                             BaselineService baselineService) {
        this.registryService = registryService;
        this.operatorFactory = operatorFactory;
        this.comparisonService = comparisonService;
        this.analyzer = analyzer;
        this.aiAnalyzer = aiAnalyzer;
        this.deepAnalyzer = deepAnalyzer;
        this.snapshotRepository = snapshotRepository;
        this.baselineService = baselineService;
    }

    /**
     * load(점유율%) = 이 쿼리의 누적 수행시간 / 상위 N개 전체 수행시간.
     * 호출수가 아니라 "시간 점유율"로 랭킹해야 DB를 실제로 붙잡고 있는 쿼리가 보인다. (PMM QAN 방식)
     */
    // callsPerSec는 스냅샷 차분으로만 낼 수 있어 이력이 없으면 null(화면 "—"). avg는 누적/호출수라 창이 필요 없다.
    public record QueryStatView(String queryId, String queryText, long calls,
                                double totalTimeMs, long rowsExamined, double loadPct,
                                double avgLatencyMs, double rowsExaminedAvg, Double callsPerSec) {
    }

    @GetMapping("/query-stats")
    public List<QueryStatView> queryStats(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        List<QueryStat> stats = operatorFactory.create(registryService.findById(id)).queryStats(limit);
        double totalTime = stats.stream().mapToDouble(QueryStat::totalTimeMs).sum();
        Map<String, Double> qpsByQuery = baselineService.recentQps(id, LocalDateTime.now());
        return stats.stream()
                .map(s -> new QueryStatView(s.queryId(), s.queryText(), s.calls(), s.totalTimeMs(),
                        s.rowsExamined(),
                        totalTime == 0 ? 0 : Math.round(s.totalTimeMs() / totalTime * 10000) / 100.0,
                        s.calls() == 0 ? 0 : s.totalTimeMs() / s.calls(),          // 평균 Latency(ms)
                        s.calls() == 0 ? 0 : (double) s.rowsExamined() / s.calls(), // 평균 Row Examined
                        qpsByQuery.get(s.queryId())))                              // Call/sec — 이력 없으면 null
                .toList();
    }

    @GetMapping("/table-stats")
    public List<TableStat> tableStats(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return operatorFactory.create(registryService.findById(id)).tableStats(limit);
    }

    /** 복제 상태 통합 뷰 — SHOW REPLICA STATUS / pg_stat_replication / AlwaysOn DMV를 하나의 모델로 */
    @GetMapping("/replication")
    public io.dbtower.operator.model.ReplicationState replication(@PathVariable Long id) {
        return operatorFactory.create(registryService.findById(id)).replicationState();
    }

    /**
     * 복제 슬롯 잔량 (C-1, PostgreSQL) — 비활성 슬롯이 WAL을 무한 보존해 디스크를 채우는 사각을 본다.
     * 슬롯 개념이 없거나 슬롯이 없는 인스턴스는 빈 목록.
     */
    @GetMapping("/replication-slots")
    public java.util.List<io.dbtower.operator.model.ReplicationSlot> replicationSlots(@PathVariable Long id) {
        return operatorFactory.create(registryService.findById(id)).replicationSlots();
    }

    /**
     * 최근 데드락 (3차 아크 D-축) — SQL Server system_health XE / MySQL INNODB STATUS에서 최근 리포트를
     * 읽는다(설정 변경 0, 롤링이라 "최근"만). PostgreSQL은 개별 사건이 없어(누적 카운터뿐) 빈 목록이고,
     * PG 데드락은 OpsAlert 카운터 델타로 알린다.
     */
    @GetMapping("/deadlocks")
    public java.util.List<io.dbtower.operator.model.DeadlockEvent> deadlocks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "10") int limit) {
        return operatorFactory.create(registryService.findById(id)).recentDeadlocks(limit);
    }

    /**
     * 대기 이벤트 (B1) — load%가 "누가 시간을 쓰나"라면 이것은 "그 시간에 무엇을 기다렸나".
     * 기종별 의미 차이(누적/순간 스냅샷/큐 지표)는 WaitEvent record 주석 참고.
     */
    @GetMapping("/wait-events")
    public List<io.dbtower.operator.model.WaitEvent> waitEvents(@PathVariable Long id,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return operatorFactory.create(registryService.findById(id)).waitEvents(limit);
    }

    @GetMapping("/slow-queries")
    public List<SlowQuery> slowQueries(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return operatorFactory.create(registryService.findById(id)).slowQueries(limit);
    }

    /**
     * 레이턴시 백분위 p95/p99 (D4a) — "평균은 멀쩡한데 꼬리가 아프다"를 보는 사용자 경험 지표. 조회는 인증 사용자(진단).
     * 같은 지표라도 기종마다 원자료가 달라 source로 출처를 구분한다: MySQL=NATIVE(QUANTILE 컬럼, 리셋 이후 누적),
     * Mongo=COMPUTED(profile 원샘플 직접 계산), PostgreSQL=ESTIMATED(평균+표준편차 근사 — 실측 아님),
     * SQL Server/Oracle=UNSUPPORTED. 라벨을 절대 섞지 않는다 — LatencyPercentile record 주석 참고.
     */
    @GetMapping("/latency-percentiles")
    public List<io.dbtower.operator.model.LatencyPercentile> latencyPercentiles(
            @PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return operatorFactory.create(registryService.findById(id)).latencyPercentiles(limit);
    }

    /**
     * 파티션 조회 (D5) — 테이블별 파티션 목록·방식·경계·행수·크기. 조회는 인증 사용자(진단). 읽기 전용이라
     * 대상 DB를 바꾸지 않는다(생성·삭제·자동 관리는 범위 밖). 기종별 소스와 필드 의미는 PartitionInfo 주석 참고:
     * MySQL/PostgreSQL/Oracle/SQL Server는 각 카탈로그, MongoDB는 파티션 개념이 없어 UNSUPPORTED.
     * 파티션이 없는 테이블/DB는 빈 결과(에러 아님).
     */
    @GetMapping("/partitions")
    public List<io.dbtower.operator.model.PartitionInfo> partitions(
            @PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        return operatorFactory.create(registryService.findById(id)).partitions(limit);
    }

    /**
     * 활성 세션 + 블로킹 트리 (B2) — "지금 누가 누구를 막고 있나". 조회는 VIEWER부터.
     * blockedByPid가 채워진 행이 곧 블로킹 관계. 기종별 pid 의미는 SessionInfo 주석 참고.
     */
    @GetMapping("/sessions")
    public List<SessionInfo> sessions(@PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        return operatorFactory.create(registryService.findById(id)).activeSessions(limit);
    }

    /** kill 결과 — 어떤 pid를 어떤 방식으로 처리했는지 그대로 돌려준다(감사와 화면 피드백용) */
    public record KillResult(long pid, boolean force, String result) {
    }

    /**
     * 세션 종료 (B2) — ADMIN만(SecurityConfig). 반드시 명시적 pid 하나만 받는다(대량 kill 없음).
     * force=false는 실행 문장 취소, force=true는 세션 강제 종료. POST라 A6 감사에 자동 기록된다.
     */
    @PostMapping("/sessions/{pid}/kill")
    public KillResult killSession(@PathVariable Long id, @PathVariable long pid,
                                  @RequestParam(defaultValue = "false") boolean force) {
        String result = operatorFactory.create(registryService.findById(id)).killSession(pid, force);
        return new KillResult(pid, force, result);
    }

    public record ExplainRequest(@NotBlank String sql) {
    }

    public record ExplainResponse(String plan, List<String> findings) {
    }

    /** 실행계획 + 규칙 기반 비효율 분석을 함께 돌려준다 */
    @PostMapping("/explain")
    public ExplainResponse explain(@PathVariable Long id, @RequestBody ExplainRequest req) {
        DatabaseInstance instance = registryService.findById(id);
        String plan = operatorFactory.create(instance).explain(req.sql());
        return new ExplainResponse(plan, analyzer.analyze(instance.getType(), plan));
    }

    public record IndexAdviceRequest(@NotBlank String sql, String columns) {
    }

    /**
     * 인덱스 어드바이저 (B3) — 후보 컬럼으로 가상 인덱스를 만들었을 때 플랜 비용이 어떻게 바뀌는지
     * 시뮬레이션한다. explain과 같은 진단이라 인증 사용자(VIEWER)면 되고, 대상 DB 상태는 바꾸지 않는다
     * (PostgreSQL은 HypoPG 가상 인덱스 — 실제 인덱스를 만들지 않는 것이 이 기능의 핵심). POST지만
     * 대상 DB를 변경하지 않으므로 ADMIN 경계에 두지 않는다(SecurityConfig 주석 참고).
     */
    @PostMapping("/index-advisor")
    public io.dbtower.operator.model.IndexAdvice indexAdvisor(@PathVariable Long id,
                                                        @RequestBody IndexAdviceRequest req) {
        return operatorFactory.create(registryService.findById(id)).adviseIndex(req.sql(), req.columns());
    }

    /**
     * 심층 원인 진단 (D9) — explain(추정)을 넘어 <b>실제 실행 계획</b>으로 "왜 인덱스를 못 타나"를 짚는다.
     * 추정 vs 실제 행수 괴리(카디널리티 오추정)의 최하위 노드 + 근본원인 5종(형변환·컬럼함수·앞와일드카드·
     * 복합 선두 누락·통계 노후)을 돌려준다. describeSchema로 컬럼 타입·인덱스 선두를 대조하며, 스키마
     * 조회가 실패해도 계획 기반 판정은 진행한다(스키마 없으면 형변환·선두 판정만 생략, 안내에 명시).
     *
     * <b>인가: ADMIN.</b> explain·index-advisor는 추정만 하고 쿼리를 실행하지 않아 VIEWER지만, 이 진단은
     * 대상 DB에서 쿼리를 <b>실제로 실행</b>한다(타임아웃은 걸지만 워크로드를 돌리는 행위). SecurityConfig의
     * 원칙("대상 DB를 바꾸거나 실행하는 행위는 ADMIN")에 따라 세션 kill·백업과 같은 ADMIN 경계에 둔다.
     */
    @PostMapping("/deep-diagnose")
    public DeepDiagnosis deepDiagnose(@PathVariable Long id, @RequestBody ExplainRequest req) {
        DatabaseInstance instance = registryService.findById(id);
        DbmsOperator operator = operatorFactory.create(instance);
        String plan = operator.explainAnalyze(req.sql());
        SchemaSnapshot schema;
        try {
            schema = operator.describeSchema();
        } catch (RuntimeException e) {
            schema = null; // 스키마 조회 실패는 치명적이지 않다 — 계획 기반 판정만으로 진행
        }
        return deepAnalyzer.diagnose(instance.getType(), req.sql(), plan, schema);
    }

    /**
     * 시점 비교 — 평소 구간(base) 대비 문제 구간(target)의 쿼리별 QPS·평균 레이턴시 증감과 신규 쿼리.
     * 예: /api/instances/1/compare?baseFrom=2026-07-02T10:00&baseTo=2026-07-02T10:10&targetFrom=2026-07-02T14:00&targetTo=2026-07-02T14:10
     */
    @GetMapping("/compare")
    public ComparisonService.CompareResult compare(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime baseFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime baseTo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime targetFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime targetTo) {
        return comparisonService.compare(id, baseFrom, baseTo, targetFrom, targetTo);
    }

    /** 활동 그래프 한 점 — 배치 간 차분으로 계산한 그 구간의 QPS/평균 레이턴시 */
    public record ActivityPoint(LocalDateTime time, double qps, double avgLatencyMs) {
    }

    /**
     * 활동 그래프 (웹 UI의 구간 선택용) — CPU·활동 그래프 위에서 조회·비교 구간을 드래그로
     * 고르게 한 것과 같은 역할. 우리는 수집 스냅샷에서 바로 뽑을 수 있는 QPS를 쓴다.
     * 원리는 시점 비교와 동일: 누적 카운터의 인접 배치 차분이 그 구간의 발생량이다.
     */
    @GetMapping("/activity")
    public List<ActivityPoint> activity(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<QuerySnapshotRepository.BatchTotal> batches = snapshotRepository.sumByBatch(id, from, to);
        List<ActivityPoint> points = new java.util.ArrayList<>();
        for (int i = 1; i < batches.size(); i++) {
            QuerySnapshotRepository.BatchTotal prev = batches.get(i - 1);
            QuerySnapshotRepository.BatchTotal cur = batches.get(i);
            long seconds = java.time.Duration.between(prev.getCapturedAt(), cur.getCapturedAt()).toSeconds();
            if (seconds <= 0) {
                continue;
            }
            // 카운터 리셋(대상 DB 재기동)이면 차분이 음수가 된다 — 0으로 클램프
            long calls = Math.max(0, cur.getTotalCalls() - prev.getTotalCalls());
            double timeMs = Math.max(0, cur.getTotalTimeMs() - prev.getTotalTimeMs());
            points.add(new ActivityPoint(cur.getCapturedAt(),
                    Math.round(calls * 100.0 / seconds) / 100.0,
                    calls == 0 ? 0 : Math.round(timeMs * 100.0 / calls) / 100.0));
        }
        return points;
    }

    /**
     * 이상 자동 감지 현재 목록 (Phase D1) — "평소(이 요일·시간대) 대비" z-score로 벗어난 쿼리를 돌려준다.
     * 시점 비교(compare)가 사람이 두 구간을 고르는 것이라면, 이건 학습된 베이스라인과 현재 구간을
     * 자동 대조한 결과다. 폴러(AnomalyDetector)가 웹훅으로 미는 것과 같은 판정을 화면에서 즉시 조회한다.
     * 이력이 부족한 쿼리는 learningCount로만 집계하고 이상 판정은 보류한다(오탐 방지). 읽기 전용이라 VIEWER 가능.
     */
    @GetMapping("/anomalies")
    public BaselineService.AnomalyScan anomalies(@PathVariable Long id) {
        registryService.findById(id); // 존재하지 않는 인스턴스면 여기서 실패(일관된 404)
        return baselineService.detectAnomalies(id, LocalDateTime.now());
    }

    public record AiAnalysisResponse(String plan, List<String> findings, String aiAnalysis) {
    }

    /**
     * EXPLAIN + 규칙 지적 + AI 1차 분석을 한 번에 — 웹 UI의 "AI 분석" 버튼용.
     * AI는 회귀 알림(RegressionDetector)과 동일한 판단 기준 프롬프트(ai-analysis-rules.md)를 쓴다.
     * ANTHROPIC_API_KEY 미설정이면 aiAnalysis=null로 규칙 지적까지만 내려간다.
     */
    @PostMapping("/ai-analysis")
    public AiAnalysisResponse aiAnalysis(@PathVariable Long id, @RequestBody ExplainRequest req) {
        DatabaseInstance instance = registryService.findById(id);
        String plan = operatorFactory.create(instance).explain(req.sql());
        List<String> findings = analyzer.analyze(instance.getType(), plan);
        String context = """
                [%s] 아래 쿼리와 실행계획을 판단 기준에 따라 분석해줘.
                SQL:
                %s
                실행계획:
                %s
                규칙 기반 지적: %s""".formatted(instance.getType(), req.sql(), plan,
                findings.isEmpty() ? "(없음)" : String.join(" / ", findings));
        return new AiAnalysisResponse(plan, findings, aiAnalyzer.analyze(context).orElse(null));
    }
}
