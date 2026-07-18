package io.dbtower.alert.internal;

import io.dbtower.alert.internal.domain.PlanSnapshot;
import io.dbtower.alert.internal.persistence.ConfigDriftDao.ParamChangeRow;
import io.dbtower.alert.internal.persistence.PlanSnapshotRepository;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.ComparisonService.CompareResult;
import io.dbtower.insight.QueryDiff;
import io.dbtower.insight.WaitEventHistoryService;
import io.dbtower.insight.WaitEventHistoryService.WaitPoint;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.dbtower.slo.SloService;
import io.dbtower.slo.SloService.HealthPoint;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 인시던트 리포트 조립 (운영 병목 아크 B4). 장애 구간을 주면 그 시간의 이야기를 이미 저장된
 * 신호들로 엮는다 — 신규 수집 0, 전부 기존 API 조합. 재료: 시점 비교(무엇이 느려졌나),
 * 설정 변경(그때 뭘 바꿨나), 플랜 플립(플랜이 갈아탔나), 대기 이벤트(무엇을 기다렸나),
 * 헬스 추이(언제 다운됐나). AI는 조립된 재료만으로 요약한다(재료에 없는 수치·원인 생성 금지).
 *
 * <p>정직: 감지 알림(회귀·이상·운영)은 영속 이력이 없어(웹훅으로만 나감) 이 타임라인에 포함하지
 * 않는다 — 리포트는 "영속된 신호로 재구성한 사건"임을 명시한다. 구간이 길면 항목별 top-N으로
 * 자르고 그 사실을 리포트에 표기한다(silent truncation 금지).
 */
@Service
public class IncidentReportService {

    /** 구간 상한 — 이보다 길면 재료가 폭증하고 "사건"의 초점이 흐려진다. */
    private static final Duration MAX_WINDOW = Duration.ofHours(24);
    private static final int TOP_QUERIES = 8;
    private static final int TOP_WAITS = 8;
    private static final int MAX_CONFIG = 30;
    private static final int MAX_PLAN = 20;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String AI_SYSTEM_PROMPT = """
            너는 DBA 인시던트 분석가다. 아래 "사건 재료"에 담긴 사실만으로 3~5문장 요약을 쓴다.
            재료에 없는 수치·원인을 지어내지 마라. 확정 대신 "가능성"으로 말하고, 설정 변경·플랜 플립과
            성능 악화의 시간적 선후를 짚어라. 재료가 빈약하면 "판단할 근거가 부족하다"고 정직하게 써라.
            """;

    private final RegistryService registryService;
    private final ComparisonService comparisonService;
    private final ConfigDriftService configDriftService;
    private final PlanSnapshotRepository planRepository;
    private final WaitEventHistoryService waitHistory;
    private final SloService sloService;
    private final AiAnalyzer aiAnalyzer;

    public IncidentReportService(RegistryService registryService, ComparisonService comparisonService,
                                 ConfigDriftService configDriftService, PlanSnapshotRepository planRepository,
                                 WaitEventHistoryService waitHistory, SloService sloService,
                                 AiAnalyzer aiAnalyzer) {
        this.registryService = registryService;
        this.comparisonService = comparisonService;
        this.configDriftService = configDriftService;
        this.planRepository = planRepository;
        this.waitHistory = waitHistory;
        this.sloService = sloService;
        this.aiAnalyzer = aiAnalyzer;
    }

    public record IncidentReport(long instanceId, String instanceName, String from, String to,
                                 String markdown, List<String> truncationNotes) {
    }

    /**
     * 리포트 생성 — [from, to] 구간. 구간이 상한을 넘으면 to를 상한으로 자르고 그 사실을 노트에 남긴다.
     * 비교 기준(base)은 같은 길이의 직전 구간이다.
     */
    public IncidentReport generate(Long instanceId, LocalDateTime from, LocalDateTime to) {
        DatabaseInstance instance = registryService.findById(instanceId); // LBAC 게이트
        List<String> notes = new ArrayList<>();
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            to = from.plus(MAX_WINDOW);
            notes.add("구간이 24시간을 넘어 " + TS.format(to) + "까지로 잘랐습니다(재료 폭증 방지).");
        }
        Duration len = Duration.between(from, to);
        LocalDateTime priorFrom = from.minus(len);

        CompareResult compare = comparisonService.compare(instanceId, priorFrom, from, from, to);
        List<ParamChangeRow> configChanges = cap(configDriftService.changesInWindow(instanceId, from, to), MAX_CONFIG, notes, "설정 변경");
        List<PlanFlip> planFlips = cap(planFlipsInWindow(instanceId, from, to), MAX_PLAN, notes, "플랜 플립");
        List<WaitPoint> waits = waitHistory.inWindow(instanceId, from, to, TOP_WAITS);
        List<HealthPoint> health = sloService.healthInWindow(instanceId, from, to);

        String facts = renderFacts(instance, from, to, compare, configChanges, planFlips, waits, health);
        String aiSummary = aiAnalyzer.complete(AI_SYSTEM_PROMPT, facts).orElse(null);
        String markdown = renderMarkdown(instance, from, to, compare, configChanges, planFlips, waits, health, aiSummary, notes);

        return new IncidentReport(instanceId, instance.getName(), TS.format(from), TS.format(to), markdown, notes);
    }

    private record PlanFlip(String queryId, LocalDateTime changedAt, String fromShape, String toShape) {
    }

    /** 창 안 스냅샷을 쿼리별로 묶어 연속 쌍(=플랜 플립)만. PlanChangeController와 같은 규칙(첫 관측은 기준선). */
    private List<PlanFlip> planFlipsInWindow(Long instanceId, LocalDateTime from, LocalDateTime to) {
        Map<String, List<PlanSnapshot>> byQuery = new LinkedHashMap<>();
        for (PlanSnapshot s : planRepository.findTop50ByInstanceIdOrderByCapturedAtDesc(instanceId)) {
            if (s.getCapturedAt().isBefore(from) || s.getCapturedAt().isAfter(to)) {
                continue;
            }
            byQuery.computeIfAbsent(s.getQueryId(), k -> new ArrayList<>()).add(s);
        }
        List<PlanFlip> flips = new ArrayList<>();
        for (List<PlanSnapshot> snaps : byQuery.values()) {
            for (int i = 0; i + 1 < snaps.size(); i++) {
                PlanSnapshot cur = snaps.get(i), prev = snaps.get(i + 1);
                flips.add(new PlanFlip(cur.getQueryId(), cur.getCapturedAt(), prev.getPlanShape(), cur.getPlanShape()));
            }
        }
        flips.sort((a, b) -> b.changedAt().compareTo(a.changedAt()));
        return flips;
    }

    private static <T> List<T> cap(List<T> list, int max, List<String> notes, String label) {
        if (list.size() <= max) {
            return list;
        }
        notes.add(label + " " + list.size() + "건 중 상위 " + max + "건만 실었습니다.");
        return list.subList(0, max);
    }

    /** AI에 넘길 사실 텍스트 — 렌더링과 같은 재료를 평문으로. */
    private String renderFacts(DatabaseInstance instance, LocalDateTime from, LocalDateTime to,
                               CompareResult compare, List<ParamChangeRow> config, List<PlanFlip> plans,
                               List<WaitPoint> waits, List<HealthPoint> health) {
        StringBuilder sb = new StringBuilder();
        sb.append("인스턴스: ").append(instance.getName()).append(" (").append(instance.getType()).append(")\n");
        sb.append("구간: ").append(TS.format(from)).append(" ~ ").append(TS.format(to)).append("\n\n");
        sb.append("[성능 비교: 직전 동일 길이 구간 대비]\n");
        sb.append("총 호출 변화 ").append(pct(compare.totalCallsChangePct()))
                .append(", 평균 지연 변화 ").append(pct(compare.avgLatencyChangePct()))
                .append(", 읽은 행수 변화 ").append(pct(compare.rowsExaminedChangePct()))
                .append(", 신규 쿼리 ").append(compare.newQueryCount()).append("개\n");
        for (QueryDiff q : topRegressions(compare)) {
            sb.append("- ").append(q.newQuery() ? "[신규] " : "").append(q.queryId())
                    .append(" 지연 ").append(fmt(q.baseAvgMs())).append("→").append(fmt(q.targetAvgMs()))
                    .append("ms (").append(pct(q.latencyChangePct())).append(")\n");
        }
        sb.append("\n[설정 변경 ").append(config.size()).append("건]\n");
        for (ParamChangeRow c : config) {
            sb.append("- ").append(TS.format(c.capturedAt())).append(" ").append(renderChange(c)).append("\n");
        }
        sb.append("\n[플랜 플립 ").append(plans.size()).append("건]\n");
        for (PlanFlip p : plans) {
            sb.append("- ").append(TS.format(p.changedAt())).append(" ").append(p.queryId())
                    .append(": ").append(p.fromShape()).append("→").append(p.toShape()).append("\n");
        }
        sb.append("\n[대기 이벤트 상위]\n");
        for (WaitPoint w : waits) {
            sb.append("- ").append(w.category()).append("/").append(w.event())
                    .append(" ").append(w.totalCount()).append("회\n");
        }
        sb.append("\n[가용성] 샘플 ").append(health.size()).append("개, 다운 ")
                .append(health.stream().filter(h -> !h.up()).count()).append("회\n");
        return sb.toString();
    }

    private String renderMarkdown(DatabaseInstance instance, LocalDateTime from, LocalDateTime to,
                                  CompareResult compare, List<ParamChangeRow> config, List<PlanFlip> plans,
                                  List<WaitPoint> waits, List<HealthPoint> health, String aiSummary, List<String> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 인시던트 리포트 — ").append(instance.getName()).append(" (").append(instance.getType()).append(")\n\n");
        sb.append("- 구간: ").append(TS.format(from)).append(" ~ ").append(TS.format(to)).append("\n");
        sb.append("- 비교 기준: 직전 동일 길이 구간\n\n");
        if (aiSummary != null && !aiSummary.isBlank()) {
            sb.append("## AI 요약\n").append(aiSummary).append("\n\n");
        }
        sb.append("## 성능 비교\n");
        sb.append("총 호출 ").append(pct(compare.totalCallsChangePct()))
                .append(" · 평균 지연 ").append(pct(compare.avgLatencyChangePct()))
                .append(" · 읽은 행수 ").append(pct(compare.rowsExaminedChangePct()))
                .append(" · 신규 쿼리 ").append(compare.newQueryCount()).append("개\n\n");
        List<QueryDiff> regs = topRegressions(compare);
        if (!regs.isEmpty()) {
            sb.append("| 쿼리 | 지연 전 | 지연 후 | 변화 |\n|---|---|---|---|\n");
            for (QueryDiff q : regs) {
                sb.append("| ").append(q.newQuery() ? "[신규] " : "").append(shortId(q.queryId()))
                        .append(" | ").append(fmt(q.baseAvgMs())).append("ms | ").append(fmt(q.targetAvgMs()))
                        .append("ms | ").append(pct(q.latencyChangePct())).append(" |\n");
            }
            sb.append("\n");
        }
        sb.append("## 설정 변경 (").append(config.size()).append(")\n");
        if (config.isEmpty()) {
            sb.append("없음\n");
        } else {
            for (ParamChangeRow c : config) {
                sb.append("- `").append(TS.format(c.capturedAt())).append("` ").append(renderChange(c)).append("\n");
            }
        }
        sb.append("\n## 플랜 플립 (").append(plans.size()).append(")\n");
        if (plans.isEmpty()) {
            sb.append("없음\n");
        } else {
            for (PlanFlip p : plans) {
                sb.append("- `").append(TS.format(p.changedAt())).append("` ").append(shortId(p.queryId()))
                        .append(": ").append(p.fromShape()).append(" → ").append(p.toShape()).append("\n");
            }
        }
        sb.append("\n## 대기 이벤트 상위\n");
        if (waits.isEmpty()) {
            sb.append("없음(미수집 또는 무대기)\n");
        } else {
            for (WaitPoint w : waits) {
                sb.append("- ").append(w.category()).append(" / ").append(w.event())
                        .append(" — ").append(String.format(Locale.ROOT, "%,d", w.totalCount())).append("회\n");
            }
        }
        long downs = health.stream().filter(h -> !h.up()).count();
        sb.append("\n## 가용성\n샘플 ").append(health.size()).append("개 중 다운 ").append(downs).append("회");
        if (downs > 0) {
            health.stream().filter(h -> !h.up()).findFirst()
                    .ifPresent(h -> sb.append(" (첫 다운 ").append(TS.format(h.sampledAt())).append(")"));
        }
        sb.append("\n\n## 재구성 한계\n");
        sb.append("- 감지 알림(회귀·이상·운영)은 영속 이력이 없어(웹훅 전용) 이 타임라인에 미포함 — ")
                .append("영속된 신호로 재구성한 사건입니다.\n");
        for (String n : notes) {
            sb.append("- ").append(n).append("\n");
        }
        return sb.toString();
    }

    private static List<QueryDiff> topRegressions(CompareResult compare) {
        return compare.queries().stream()
                .filter(q -> q.latencyChangePct() == null || q.latencyChangePct() > 0 || q.newQuery())
                .sorted((a, b) -> Double.compare(b.targetAvgMs(), a.targetAvgMs()))
                .limit(TOP_QUERIES)
                .toList();
    }

    private static String renderChange(ParamChangeRow c) {
        return switch (c.kind()) {
            case "ADDED" -> c.paramName() + ": (없음) → " + c.newValue();
            case "REMOVED" -> c.paramName() + ": " + c.oldValue() + " → (제거)";
            default -> c.paramName() + ": " + c.oldValue() + " → " + c.newValue();
        };
    }

    private static String pct(Double p) {
        return p == null ? "n/a" : String.format(Locale.ROOT, "%+.1f%%", p);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String shortId(String id) {
        return id == null ? "" : (id.length() > 24 ? id.substring(0, 24) + "…" : id);
    }
}
