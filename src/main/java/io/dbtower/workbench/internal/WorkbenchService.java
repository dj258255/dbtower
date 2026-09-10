package io.dbtower.workbench.internal;

import io.dbtower.analysis.QueryMasker;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.QueryResult;
import io.dbtower.operator.model.ResultColumn;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.ConsoleCredentialService;
import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.RegistryService;
import io.dbtower.workbench.StatementClassifier;
import io.dbtower.workbench.StatementClassifier.Classification;
import io.dbtower.workbench.StatementClassifier.Tier;
import io.dbtower.workbench.internal.ResultMasker.Masked;
import io.dbtower.workbench.internal.ResultMasker.Policy;
import io.dbtower.workbench.internal.domain.MaskingRule;
import io.dbtower.workbench.internal.domain.MaskingStrategy;
import io.dbtower.workbench.internal.domain.WorkbenchQueryLog;
import io.dbtower.workbench.internal.persistence.MaskingRuleRepository;
import io.dbtower.workbench.internal.persistence.WorkbenchQueryLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 워크벤치 조회의 정책 계층 — 문장 분류, 콘솔 계정 확인, 읽기 전용 실행, 결과 마스킹, 실행 기록을 한 흐름으로 묶는다.
 *
 * <p>순서가 계약이다: 분류에서 읽기가 아니면 대상 DB에 닿기 전에 끝나고, 조회 계정이 없으면 모니터 계정으로
 * 대신 실행하지 않고 끝난다(fail-closed). 거부·실패도 성공과 같은 기록 테이블에 남긴다 — 감사는 "무엇이 막혔나"가 절반이다.
 */
@Service
public class WorkbenchService {

    private static final int STATEMENT_LOG_MAX = 4_000;
    private static final int EXPORT_REASON_MIN = 5;
    private static final int COMPARE_CHANGES_MAX = 200;

    private final RegistryService registry;
    private final ConsoleCredentialService credentials;
    private final DbmsOperatorFactory operators;
    private final MaskingRuleRepository maskingRules;
    private final WorkbenchQueryLogRepository logs;
    private final QueryMasker queryMasker;
    private final int defaultRowLimit;
    private final int timeoutSeconds;

    public WorkbenchService(RegistryService registry, ConsoleCredentialService credentials,
                            DbmsOperatorFactory operators, MaskingRuleRepository maskingRules,
                            WorkbenchQueryLogRepository logs, QueryMasker queryMasker,
                            @Value("${dbtower.workbench.default-row-limit:200}") int defaultRowLimit,
                            @Value("${dbtower.workbench.timeout-seconds:15}") int timeoutSeconds) {
        this.registry = registry;
        this.credentials = credentials;
        this.operators = operators;
        this.maskingRules = maskingRules;
        this.logs = logs;
        this.queryMasker = queryMasker;
        this.defaultRowLimit = defaultRowLimit;
        this.timeoutSeconds = timeoutSeconds;
    }

    public record InstanceView(Long id, String name, String type, String teamLabel, String environment,
                               boolean readConfigured) {
    }

    public record QueryView(Classification classification, List<ResultColumn> columns, List<List<Object>> rows,
                            int rowCount, boolean truncated, long elapsedMs, List<String> maskedColumns) {
    }

    public record CsvExport(String filename, String body, int rowCount, boolean truncated) {
    }

    public record HistoryItem(LocalDateTime occurredAt, String action, String tier, String kind, String statement,
                              String outcome, Integer rowCount, Long elapsedMs, String error) {
    }

    public record RuleView(Long id, Long instanceId, String columnPattern, MaskingStrategy strategy, String note) {
    }

    /** 실행 전에 거부됐거나 실행이 실패한 요청 — 컨트롤러가 status를 HTTP 상태로 옮긴다. */
    public static class WorkbenchRejection extends RuntimeException {
        private final int status;
        private final transient Classification classification;

        WorkbenchRejection(int status, String message, Classification classification) {
            super(message);
            this.status = status;
            this.classification = classification;
        }

        public int status() {
            return status;
        }

        public Classification classification() {
            return classification;
        }
    }

    public List<InstanceView> instances() {
        return registry.findAll().stream()
                .map(i -> new InstanceView(i.getId(), i.getName(), i.getType().name(), i.getTeamLabel(),
                        i.getEnvironment(),
                        credentials.summaries(i.getId()).stream().anyMatch(s -> s.purpose() == CredentialPurpose.READ)))
                .toList();
    }

    public Classification classify(Long instanceId, String statement) {
        registry.findById(instanceId);
        return StatementClassifier.classify(statement);
    }

    public QueryView run(Long instanceId, String statement, Integer rowLimit) {
        Execution e = execute(instanceId, statement, rowLimit == null ? defaultRowLimit : rowLimit, "QUERY", null);
        return new QueryView(e.classification(), e.result().columns(), e.masked().rows(), e.result().rowCount(),
                e.result().truncated(), e.result().elapsedMs(), e.masked().maskedColumns());
    }

    /**
     * CSV 내보내기 — 조회와 같은 정책 흐름에 사유를 필수로 받는다. 화면에서 보는 것과 파일로 가져가는 것은
     * 유출 반경이 달라서다. 행 상한은 오퍼레이터 상한(1000행)을 그대로 쓴다.
     */
    public CsvExport export(Long instanceId, String statement, String reason) {
        if (reason == null || reason.strip().length() < EXPORT_REASON_MIN) {
            throw new WorkbenchRejection(400, "내보내기 사유를 " + EXPORT_REASON_MIN + "자 이상 적어야 합니다", null);
        }
        Execution e = execute(instanceId, statement, DbmsOperator.MAX_ROW_LIMIT, "EXPORT", reason.strip());
        String filename = "workbench-" + e.instance().getName() + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv";
        return new CsvExport(filename, CsvWriter.write(e.result().columns(), e.masked().rows()),
                e.result().rowCount(), e.result().truncated());
    }

    public record CompareSide(Long instanceId, String name, String type, int rowCount, boolean truncated, long elapsedMs) {
    }

    public record CompareView(Classification classification, CompareSide left, CompareSide right, RowDiff.Result diff,
                              List<String> maskedColumns) {
    }

    /**
     * 같은 조회를 두 인스턴스에서 실행해 결과를 행 단위로 비교한다(스테이징 대 운영, 원본 대 복제 등). 양쪽 다 조회 경로를 그대로
     * 탄다 — 각 인스턴스의 조회 계정·읽기 전용·기록. 차이 판정은 원래 값으로 하고, 보여줄 때는 두 인스턴스 마스킹 규칙의 합집합으로
     * 가린다(한쪽에서만 가리는 열이 비교 화면으로 새지 않게).
     */
    public CompareView compare(Long leftId, Long rightId, String statement, List<String> keyColumns, Integer rowLimit) {
        if (leftId == null || rightId == null || Objects.equals(leftId, rightId)) {
            throw new WorkbenchRejection(400, "서로 다른 두 인스턴스를 골라야 합니다", null);
        }
        int limit = rowLimit == null ? defaultRowLimit : rowLimit;
        Execution left = execute(leftId, statement, limit, "COMPARE", null);
        Execution right = execute(rightId, statement, limit, "COMPARE", null);
        RowDiff.Result diff = RowDiff.diff(names(left.result()), left.result().rows(), names(right.result()),
                right.result().rows(), keyColumns == null ? List.of() : keyColumns, COMPARE_CHANGES_MAX);
        if (left.result().truncated() || right.result().truncated()) {
            diff = diff.withNote("행 상한(" + limit + "행)에 걸려 일부 행만 비교했다. 차이가 상한 밖에 있을 수 있어 조건이나 키로 좁혀야 한다");
        }
        List<Policy> policies = new ArrayList<>(policies(leftId));
        policies.addAll(policies(rightId));
        ResultMasker.MaskedDiff masked = ResultMasker.maskDiff(diff, policies);
        return new CompareView(left.classification(), side(left), side(right), masked.diff(), masked.maskedColumns());
    }

    private static CompareSide side(Execution e) {
        return new CompareSide(e.instance().getId(), e.instance().getName(), e.instance().getType().name(),
                e.result().rowCount(), e.result().truncated(), e.result().elapsedMs());
    }

    private static List<String> names(QueryResult result) {
        return result.columns().stream().map(ResultColumn::name).toList();
    }

    public List<HistoryItem> history(Long instanceId, int limit) {
        registry.findById(instanceId);
        return logs.findByInstanceIdAndPrincipalOrderByOccurredAtDesc(instanceId, currentPrincipal(),
                        PageRequest.of(0, Math.clamp(limit, 1, 100))).stream()
                .map(l -> new HistoryItem(l.getOccurredAt(), l.getAction(), l.getTier(), l.getKind(), l.getStatement(),
                        l.getOutcome(), l.getRowCount(), l.getElapsedMs(), l.getError()))
                .toList();
    }

    public List<RuleView> maskingRules(Long instanceId) {
        if (instanceId != null) {
            registry.findById(instanceId);
        }
        return maskingRules.findApplicable(instanceId).stream().map(WorkbenchService::ruleView).toList();
    }

    public RuleView addMaskingRule(Long instanceId, String columnPattern, MaskingStrategy strategy, String note) {
        if (instanceId != null) {
            registry.findById(instanceId);
        }
        String pattern = columnPattern == null ? "" : columnPattern.strip().toLowerCase();
        if (!pattern.matches("[a-z0-9_*]{1,100}") || pattern.chars().allMatch(ch -> ch == '*')) {
            throw new WorkbenchRejection(400, "컬럼 패턴은 영소문자·숫자·밑줄·*만 쓰고, *만으로 이뤄질 수 없습니다", null);
        }
        return ruleView(maskingRules.save(new MaskingRule(instanceId, pattern, strategy, note)));
    }

    public void deleteMaskingRule(Long ruleId) {
        maskingRules.deleteById(ruleId);
    }

    private record Execution(DatabaseInstance instance, Classification classification, QueryResult result,
                             Masked masked) {
    }

    private Execution execute(Long instanceId, String statement, int rowLimit, String action, String reason) {
        String principal = currentPrincipal();
        DatabaseInstance instance = registry.findById(instanceId);
        Classification c = StatementClassifier.classify(statement);
        if (c.tier() != Tier.READ) {
            record(principal, instanceId, action, c, statement, "REJECTED", null, reason, c.reason());
            // 변경 문장은 "여기가 아니라 변경 요청으로" 가라는 뜻이라 409, 차단은 요청 자체가 잘못이라 400
            throw new WorkbenchRejection(c.tier() == Tier.NEEDS_APPROVAL ? 409 : 400, c.reason(), c);
        }
        ConsoleCredential credential = credentials.find(instanceId, CredentialPurpose.READ).orElse(null);
        if (credential == null) {
            String message = "이 인스턴스에는 조회 계정(READ)이 없습니다. ADMIN이 콘솔 계정을 등록해야 워크벤치를 쓸 수 있습니다";
            record(principal, instanceId, action, c, statement, "REJECTED", null, reason, message);
            throw new WorkbenchRejection(409, message, c);
        }
        try {
            QueryResult result = operators.create(instance).executeReadOnly(credential, statement, rowLimit, timeoutSeconds);
            Masked masked = ResultMasker.apply(result.columns(), result.rows(), policies(instanceId), statement);
            record(principal, instanceId, action, c, statement, "OK", new Outcome(result, masked), reason, null);
            return new Execution(instance, c, result, masked);
        } catch (OperatorException | IllegalArgumentException | UnsupportedOperationException ex) {
            record(principal, instanceId, action, c, statement, "ERROR", null, reason, ex.getMessage());
            throw new WorkbenchRejection(422, ex.getMessage(), c);
        }
    }

    private record Outcome(QueryResult result, Masked masked) {
    }

    List<Policy> policies(Long instanceId) {
        return maskingRules.findApplicable(instanceId).stream()
                .map(r -> new Policy(r.getColumnPattern(), r.getStrategy()))
                .toList();
    }

    private void record(String principal, Long instanceId, String action, Classification c, String statement,
                        String outcome, Outcome result, String reason, String error) {
        // 기록에는 리터럴을 가린 문장을 남긴다 — WHERE email = '...'의 값이 감사 테이블로 새지 않게
        String masked = queryMasker.apply(statement == null ? "" : statement);
        logs.save(new WorkbenchQueryLog(principal, instanceId, action, c.tier().name(), c.kind(),
                truncate(masked, STATEMENT_LOG_MAX), outcome,
                result == null ? null : result.result().rowCount(),
                result == null ? null : result.result().truncated(),
                result == null ? null : truncate(String.join(",", result.masked().maskedColumns()), 500),
                result == null ? null : result.result().elapsedMs(),
                truncate(reason, 500), truncate(error, 500)));
    }

    private static RuleView ruleView(MaskingRule r) {
        return new RuleView(r.getId(), r.getInstanceId(), r.getColumnPattern(), r.getStrategy(), r.getNote());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }
}
