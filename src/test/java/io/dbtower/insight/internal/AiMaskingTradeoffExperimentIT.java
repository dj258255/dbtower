package io.dbtower.insight.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.analysis.AiAnalyzer.CallSite;
import io.dbtower.analysis.AiMaskLevel;
import io.dbtower.analysis.PlanMasker;
import io.dbtower.analysis.TableScale;
import io.dbtower.analysis.QueryMasker;
import io.dbtower.analysis.RuleBasedAnalyzer;
import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.internal.MySqlOperator;
import io.dbtower.operator.internal.PostgresOperator;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableDetail;
import io.dbtower.operator.model.TableSchema;
import io.dbtower.registry.DbmsType;
import io.dbtower.testsupport.TargetTableLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E2 — 마스킹 토글이 가리는 것과 그 대가를 실측한다.
 *
 * <p>{@code AiAnalysisRunner}는 AI 프롬프트에 SQL + 실행계획 + 규칙 기반 지적을 넣는데, 토글
 * ({@code dbtower.masking.mask-ai-prompt})은 <b>SQL 문장만</b> 가린다. 실행계획은 원문 그대로 들어가고
 * MySQL/PG의 실행계획에는 조건 값이 찍히므로, 토글을 켜도 값이 계획을 타고 나갈 수 있다. 이 실험은
 * 그 노출과 정확도의 대가를 같은 사례 15개로 세 조건에서 함께 잰다.
 *
 * <pre>
 * 조건 A: SQL 원문        + 계획 원문          (제품 기본값)
 * 조건 B: SQL ? 마스킹    + 계획 원문          (토글을 켠 실제 동작)
 * 조건 C: SQL ? 마스킹    + 계획의 작은따옴표 문자열만 '?'
 * </pre>
 *
 * <p>프롬프트는 제품 {@code AiAnalysisRunner.run}과 글자 단위로 같아야 한다 — {@link #프롬프트가_제품_AiAnalysisRunner와_글자_단위로_같다()}
 * 가 그 동일성을 증명한다(제품 팩토리를 대체하고 AiAnalyzer를 Mockito로 붙잡아 맞댄다).
 *
 * <p>게이트: {@code DBTOWER_EXPERIMENT=1 ./gradlew cleanTest test --tests '*AiMaskingTradeoffExperimentIT'}
 * docker compose의 대상 DB(MySQL 13306, PostgreSQL 15432)가 필요하다. 모델은 제품 {@link AiAnalyzer}를
 * 그대로 쓴다(이 환경은 API 키가 없어 claude CLI 모드). 실험 테이블 {@code exp_mask_*}는 {@code @AfterAll}에서 지운다.
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_EXPERIMENT", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiMaskingTradeoffExperimentIT {

    static final String GATE = "DBTOWER_EXPERIMENT";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 제품 기본 모델 이름 — CLI 모드에서는 제품이 --model을 넘기지 않으므로 실제 모델은 CLI 기본값이다 */
    private static final String MODEL = "claude-opus-4-8";
    private static final String RULES_PATH = "docs/ai-analysis-rules.md";
    private static final long MAX_TOKENS = 8192;
    private static final String EFFORT = "high";

    private static final Path CASES_PATH = Path.of("src", "test", "resources", "experiments", "ai-masking-cases.json");
    private static final Path RESPONSES_PATH = Path.of("docs", "experiments", "ai-masking-responses.jsonl");
    private static final Path DOC_PATH = Path.of("docs", "experiments", "ai-masking-tradeoff.md");

    /**
     * 프롬프트에 대상 테이블 행수가 붙기 전(#124 이전) 회차의 원자료. 지우지 않고 남긴다 —
     * 그 회차의 결론(노출 41 -> 0, 앞 와일드카드가 진단을 가른다)은 그 조건에서 참이고,
     * 조건이 달라졌다는 사실과 함께 읽으면 비교의 재료가 된다(#130).
     */
    static final Path RESPONSES_BEFORE_SCALE =
            Path.of("docs", "experiments", "ai-masking-responses-noscale.jsonl");

    private static final List<String> CONDITIONS = List.of("A", "B", "C");

    /** 조건 간 순서 편향을 막기 위해 호출 순서를 고정 시드로 섞는다 — 이어서 돌릴 때도 같은 순서가 나온다 */
    private static final long SHUFFLE_SEED = 20260916L;

    private static final int CUSTOMERS = 100_000;
    private static final int ORDERS = 200_000;

    private static final ConnectionPools POOLS =
            new ConnectionPools(new VaultCredentials("", ""), 15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    private static final List<Db> DBS = new ArrayList<>();

    // ---------------------------------------------------------------- 준비

    /** 같은 대상 DB에 다른 실행이 붙어 exp_mask_* 를 지우지 않게 잡는다(#112) */
    private static TargetTableLock targetLock;

    @BeforeAll
    static void setUp() throws Exception {
        targetLock = TargetTableLock.acquireIfEnabled("DBTOWER_EXPERIMENT", List.of(
                new TargetTableLock.Target("jdbc:mysql://127.0.0.1:13306/sample", "root", "dbtower1234"),
                new TargetTableLock.Target("jdbc:postgresql://127.0.0.1:15432/sample", "postgres", "dbtower1234")));
        DBS.clear();
        DatabaseInstance mysql = instance(9501, DbmsType.MYSQL, 13306, "sample", "root", "dbtower1234");
        DBS.add(new Db("MySQL", mysql, new ConsoleCredential("root", "dbtower1234"),
                "jdbc:mysql://127.0.0.1:13306/sample?connectTimeout=3000&socketTimeout=30000",
                new MySqlOperator(mysql, POOLS, null, null)));
        DatabaseInstance postgres = instance(9502, DbmsType.POSTGRESQL, 15432, "sample", "postgres", "dbtower1234");
        DBS.add(new Db("PostgreSQL", postgres, new ConsoleCredential("postgres", "dbtower1234"),
                "jdbc:postgresql://127.0.0.1:15432/sample?connectTimeout=3&socketTimeout=30",
                new PostgresOperator(postgres, POOLS, null)));
        for (Db db : DBS) {
            prepare(db);
        }
        Files.createDirectories(DOC_PATH.getParent());
    }

    @AfterAll
    static void releaseLock() {
        if (targetLock != null) {
            targetLock.close();
        }
    }

    @AfterAll
    static void tearDown() {
        for (Db db : DBS) {
            try {
                exec(db, "DROP TABLE IF EXISTS exp_mask_orders", "DROP TABLE IF EXISTS exp_mask_customers");
                System.out.println("[E2] " + db.dbms() + " exp_mask_* 정리 완료");
            } catch (SQLException e) {
                System.out.println("[E2] " + db.dbms() + " 정리 실패: " + e.getMessage());
            }
        }
        POOLS.closeAll();
    }

    // ---------------------------------------------------------------- 완료 조건 2: 프롬프트 동일성

    @Test
    @Order(1)
    void 프롬프트가_제품_AiAnalysisRunner와_글자_단위로_같다() throws Exception {
        int checked = assertPromptIdentity();
        System.out.println("[E2] 프롬프트 동일성 확인 — 사례 " + checked + "개 × 조건 A·B");
    }

    /**
     * 제품 경로({@code AiAnalysisRunner.run})가 만든 프롬프트와 실행기가 만든 프롬프트를 글자 단위로 맞댄다.
     * 본 실험도 호출을 시작하기 전에 이 검사를 먼저 지난다 — 형식이 어긋난 채로 모델을 부르지 않는다.
     */
    private static int assertPromptIdentity() throws Exception {
        List<MaskCase> cases = loadCases();
        String plan = """
                {
                  "query_block": {
                    "select_id": 1,
                    "table": {"table_name": "exp_mask_customers", "access_type": "ALL",
                              "attached_condition": "phone = 1012345678"},
                    "ordering_operation": {"using_filesort": true}
                  }
                }""";
        RuleBasedAnalyzer rules = new RuleBasedAnalyzer();
        for (MaskCase c : cases) {
            DbmsType type = DbmsType.valueOf(c.dbms().toUpperCase(Locale.ROOT));
            List<String> findings = rules.analyze(type, plan);
            for (String condition : List.of("A", "B")) {
                boolean maskAiPrompt = "B".equals(condition);
                String product = productPrompt(c, plan, maskAiPrompt);
                String runner = prompt(type, sqlFor(c, condition), plan, findings, expectedScale(c));
                assertEquals(product, runner, c.id() + " 조건 " + condition + " 의 프롬프트가 제품과 다르다");
            }
        }
        return cases.size();
    }

    /**
     * 제품 경로로 프롬프트를 받아낸다: 팩토리를 대체하고 AiAnalyzer가 받은 인자를 붙잡는다.
     *
     * <p>스키마와 테이블 상세도 대역으로 돌려준다 — {@link TableScale}이 행수 줄을 붙이는 경우까지
     * 맞대야 한다. 대역이 빈 스키마를 주면 그 줄이 없는 프롬프트만 비교하게 되고, 제품이 실제로
     * 붙이는 줄은 검사에서 빠진다(#130에서 실제로 그렇게 새고 있었다).
     */
    private static String productPrompt(MaskCase c, String plan, boolean maskAiPrompt) {
        DbmsOperator operator = mock(DbmsOperator.class);
        when(operator.explain(anyString())).thenReturn(plan);
        stubScale(operator);
        DbmsOperatorFactory factory = mock(DbmsOperatorFactory.class);
        when(factory.create(any())).thenReturn(operator);
        AiAnalyzer analyzer = mock(AiAnalyzer.class);
        AiAnalysisRunner runner = new AiAnalysisRunner(factory, new RuleBasedAnalyzer(), analyzer,
                new QueryMasker(true, maskAiPrompt), new PlanMasker(true, false));

        runner.run(instanceFor(c), c.sql(), AiAnalysisRunner.Listener.NONE);

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(analyzer).analyze(eq(CallSite.EXPLAIN), captor.capture());
        return captor.getValue();
    }

    /**
     * 사례의 SQL에 나오는 테이블에 행수를 붙여 준다 — 실험과 제품이 같은 줄을 받게 한다.
     * 행수는 실제 시드와 같은 값으로 둔다({@link #CUSTOMERS}·{@link #ORDERS}).
     */
    private static void stubScale(DbmsOperator operator) {
        when(operator.describeSchema()).thenReturn(new SchemaSnapshot("TABLE", "sample",
                List.of(tableSchema("exp_mask_customers"), tableSchema("exp_mask_orders")), false, 0));
        when(operator.tableDetail("exp_mask_customers")).thenReturn(detailWithRows("exp_mask_customers", CUSTOMERS));
        when(operator.tableDetail("exp_mask_orders")).thenReturn(detailWithRows("exp_mask_orders", ORDERS));
    }

    /** 동일성 비교에 쓸 행수 줄 — 대역 스키마·상세로 TableScale이 만들 줄을 그대로 얻는다. */
    private static String expectedScale(MaskCase c) {
        DbmsOperator operator = mock(DbmsOperator.class);
        stubScale(operator);
        return TableScale.describe(operator, c.sql());
    }

    private static TableSchema tableSchema(String name) {
        return new TableSchema(name, List.of(), List.of(), "TABLE", List.of(), List.of(), null, null);
    }

    private static TableDetail detailWithRows(String name, long rows) {
        return new TableDetail(name, null, rows, -1, -1, -1, null, null,
                TableDetail.DdlSource.UNSUPPORTED, List.of(), "대역");
    }

    private static DatabaseInstance instanceFor(MaskCase c) {
        for (Db db : DBS) {
            if (db.dbms().equals(c.dbms())) {
                return db.instance();
            }
        }
        throw new IllegalArgumentException("사례의 기종을 찾을 수 없다: " + c.dbms());
    }

    /** 제품과 같은 형식 — 조건 A·B의 SQL은 제품과 같은 QueryMasker를 통과한다. */
    /**
     * 실험 프롬프트 — 제품 {@code AiAnalysisRunner.run}과 글자 단위로 같아야 한다.
     *
     * <p>{@code scale}은 대상 테이블의 전체 행수 한 줄이다(#124, {@link TableScale}). 선택도는 비율이라
     * 분모가 없으면 판단할 수 없어 제품이 이 줄을 붙이는데, 실험이 빼면 두 쪽의 조건이 달라진다 —
     * 199절까지의 측정(조건 A~E)은 이 줄 없이 쟀고 가림 수준 측정은 붙여서 쟀다. 그래서 여기서 맞춘다(#130).
     */
    private static String prompt(DbmsType type, String sqlForPrompt, String plan, List<String> findings,
                                 String scale) {
        return """
                [%s] 아래 쿼리와 실행계획을 판단 기준에 따라 분석해줘.
                SQL:
                %s
                실행계획:
                %s
                규칙 기반 지적: %s%s""".formatted(type, sqlForPrompt, plan,
                findings.isEmpty() ? "(없음)" : String.join(" / ", findings),
                scale.isEmpty() ? "" : "\n" + scale);
    }

    private static String sqlFor(MaskCase c, String condition) {
        return switch (condition) {
            case "A" -> new QueryMasker(true, false).applyForAiPrompt(c.sql());
            case "B", "C" -> new QueryMasker(true, true).applyForAiPrompt(c.sql());
            default -> throw new IllegalArgumentException(condition);
        };
    }

    // ---------------------------------------------------------------- 본 실험

    @Test
    @Order(2)
    void 마스킹_토글이_가리는_것과_정확도_대가를_잰다() throws Exception {
        String startedAt = OffsetDateTime.now().toString();
        // 모델을 한 번이라도 부르기 전에 프롬프트 동일성을 다시 확인한다 — 형식이 어긋나면 여기서 멈춘다
        assertPromptIdentity();
        List<MaskCase> cases = loadCases();
        assertEquals(15, cases.size(), "사례 세트는 15개여야 한다(모델 호출 45회 = 15 × 3)");

        AiAnalyzer analyzer = new AiAnalyzer(new SimpleMeterRegistry(), MODEL, RULES_PATH, MAX_TOKENS, EFFORT);
        if (!analyzer.isEnabled()) {
            fail("AI 백엔드가 OFF다(ANTHROPIC_API_KEY도 claude CLI도 없다) — 빈 응답을 정확도 0으로 세지 않으려 여기서 멈춘다");
        }
        String mode = analyzer.backend();

        // 조건별 컨텍스트 — 계획은 제품과 같은 경로(오퍼레이터 explain)에서 가져온다
        Map<String, Map<String, String>> prompts = new LinkedHashMap<>();
        Map<String, String> plans = new LinkedHashMap<>();
        for (MaskCase c : cases) {
            Db db = dbFor(c.dbms());
            String plan = db.op().explain(c.sql());
            List<String> findings = new RuleBasedAnalyzer().analyze(typeOf(c), plan);
            String scale = TableScale.describe(db.op(), c.sql());
            plans.put(c.id(), plan);
            Map<String, String> perCondition = new LinkedHashMap<>();
            for (String condition : CONDITIONS) {
                String planForPrompt = "C".equals(condition) ? maskPlanStringLiterals(plan) : plan;
                perCondition.put(condition, prompt(typeOf(c), sqlFor(c, condition), planForPrompt, findings, scale));
            }
            prompts.put(c.id(), perCondition);
            System.out.println("[E2] " + c.id() + " 계획 확보 — " + plan.replaceAll("\\s+", " ").length() + "자, 규칙 지적 "
                    + findings.size() + "건");
        }

        // 실행 순서 — 고정 시드로 섞고, 이미 기록된 (사례, 조건)은 다시 부르지 않는다
        List<String[]> order = new ArrayList<>();
        for (MaskCase c : cases) {
            for (String condition : CONDITIONS) {
                order.add(new String[]{c.id(), condition});
            }
        }
        java.util.Collections.shuffle(order, new Random(SHUFFLE_SEED));
        Set<String> done = recordedPairs();

        for (int i = 0; i < order.size(); i++) {
            String caseId = order.get(i)[0];
            String condition = order.get(i)[1];
            if (done.contains(caseId + "|" + condition)) {
                System.out.println("[E2] " + (i + 1) + "/" + order.size() + " " + caseId + "/" + condition + " 건너뜀(이미 기록됨)");
                continue;
            }
            MaskCase c = cases.stream().filter(x -> x.id().equals(caseId)).findFirst().orElseThrow();
            long t0 = System.nanoTime();
            String response = "";
            String error = null;
            try {
                response = analyzer.analyze(CallSite.EXPLAIN, prompts.get(caseId).get(condition)).orElse("");
                if (response.isEmpty()) {
                    error = "빈 응답 — AiAnalyzer가 실패를 빈 값으로 내려보냈다(상세 사유는 로그)";
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            double seconds = (System.nanoTime() - t0) / 1_000_000_000.0;
            appendResponse(caseId, condition, i + 1, mode, analyzer.backend(), seconds, prompts.get(caseId).get(condition),
                    response, error);
            System.out.println("[E2] " + (i + 1) + "/" + order.size() + " " + caseId + "/" + condition
                    + " " + String.format(Locale.ROOT, "%.1fs", seconds) + (error == null ? " 응답 " + response.length() + "자"
                    : " 오류: " + error));
        }

        List<JsonNode> recorded = readResponses();
        assertEquals(45, recorded.size(), "jsonl은 45줄(15사례 × 3조건)이어야 한다");

        Exposure exposure = exposure(cases, prompts);
        Scoring scoring = scoring(cases, recorded);
        Files.writeString(DOC_PATH, document(dbEnvironments(), cases, plans, exposure, scoring, recorded, startedAt, mode),
                StandardCharsets.UTF_8);
        System.out.println("[E2] 결과 기록: " + DOC_PATH.toAbsolutePath());

        long errors = recorded.stream().filter(n -> !n.path("error").isNull() && !n.path("error").asText().isEmpty()).count();
        System.out.println("[E2] 호출 45회 중 오류 " + errors + "건. 조건별 자동 채점(L사례/C사례) — "
                + scoring.summaryLine());

        // 화면에 보이는 숫자와 문서의 숫자가 같은 곳에서 나오게, 문서에 쓴 값을 그대로 다시 확인한다
        assertEquals(45, recorded.size());
        for (MaskCase c : cases) {
            assertTrue(prompts.get(c.id()).get("A").contains(c.sql()), "조건 A 프롬프트에 원문 SQL이 없다");
            String masked = new QueryMasker(true, true).applyForAiPrompt(c.sql());
            for (String condition : List.of("B", "C")) {
                assertTrue(prompts.get(c.id()).get(condition).contains(masked), condition + " 프롬프트에 마스킹된 SQL이 없다");
            }
        }
    }

    // ---------------------------------------------------------------- 반복 (E2')

    private static final Path REPEAT_PATH = Path.of("docs", "experiments", "ai-masking-repeat.jsonl");

    /**
     * 사례마다 1회 호출이던 E2를 반복해 응답 편차를 본다. 설계 오류로 뺀 L7·C4는 부르지 않는다.
     * B·C는 E2 응답을 1회차로 쓰고 2·3회차만 부른다. D(계획 문자열의 값은 지우고 앞뒤 %만 남김)와
     * E(제품 {@link PlanMasker} — 조건 키 안에서 숫자까지 가림)는 1~3회차를 부른다.
     * 판정은 이 테스트가 하지 않는다 — 응답 원문을 남기고 판정은 별도 스크립트가 사람 판정과 대조한 뒤 한다.
     * 게이트: DBTOWER_EXPERIMENT=1 과 DBTOWER_EXPERIMENT_REPEAT=1
     */
    @Test
    @Order(3)
    void 반복_호출로_조건별_응답_편차를_잰다() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("DBTOWER_EXPERIMENT_REPEAT")),
                "DBTOWER_EXPERIMENT_REPEAT=1 일 때만 반복 호출한다");
        assertPromptIdentity();
        List<MaskCase> cases = loadCases().stream().filter(c -> !List.of("L7", "C4").contains(c.id())).toList();
        assertEquals(13, cases.size());
        AiAnalyzer analyzer = new AiAnalyzer(new SimpleMeterRegistry(), MODEL, RULES_PATH, MAX_TOKENS, EFFORT);
        if (!analyzer.isEnabled()) {
            fail("AI 백엔드가 OFF다 — 빈 응답을 정확도 0으로 세지 않으려 여기서 멈춘다");
        }
        Map<String, Map<String, String>> prompts = new LinkedHashMap<>();
        for (MaskCase c : cases) {
            String plan = dbFor(c.dbms()).op().explain(c.sql());
            List<String> findings = new RuleBasedAnalyzer().analyze(typeOf(c), plan);
            String scale = TableScale.describe(dbFor(c.dbms()).op(), c.sql());
            Map<String, String> perCondition = new LinkedHashMap<>();
            perCondition.put("B", prompt(typeOf(c), sqlFor(c, "B"), plan, findings, scale));
            perCondition.put("C", prompt(typeOf(c), sqlFor(c, "C"), maskPlanStringLiterals(plan), findings, scale));
            perCondition.put("D", prompt(typeOf(c), sqlFor(c, "C"), maskPlanKeepWildcards(plan), findings, scale));
            // 조건 E는 실험 장치가 아니라 제품 함수다 — PlanMasker가 조건 키 안에서만 가리고 숫자까지 가린다.
            // C·D가 작은따옴표만 보는 탓에 계획의 맨숫자가 남았고(L9·L10), 그 구멍을 메운 것이 이 조건이다.
            perCondition.put("E", prompt(typeOf(c), sqlFor(c, "C"),
                    PlanMasker.maskPlan(typeOf(c), plan), findings, scale));
            prompts.put(c.id(), perCondition);
        }
        Set<String> done = new TreeSet<>();
        if (Files.exists(REPEAT_PATH)) {
            for (String line : Files.readAllLines(REPEAT_PATH, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    JsonNode n = MAPPER.readTree(line);
                    if (n.path("error").isNull()) {
                        done.add(n.path("caseId").asText() + "|" + n.path("condition").asText() + "|" + n.path("rep").asInt());
                    }
                }
            }
        }
        List<String[]> order = new ArrayList<>();
        for (MaskCase c : cases) {
            for (String condition : List.of("B", "C", "D", "E")) {
                for (int rep = List.of("D", "E").contains(condition) ? 1 : 2; rep <= 3; rep++) {
                    if (!done.contains(c.id() + "|" + condition + "|" + rep)) {
                        order.add(new String[]{c.id(), condition, String.valueOf(rep)});
                    }
                }
            }
        }
        java.util.Collections.shuffle(order, new Random(SHUFFLE_SEED + 1));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        for (String[] item : order) {
            futures.add(pool.submit(() -> {
                String p = prompts.get(item[0]).get(item[1]);
                long t0 = System.nanoTime();
                String response = "";
                String error = null;
                try {
                    response = analyzer.analyze(CallSite.EXPLAIN, p).orElse("");
                    if (response.isEmpty()) {
                        error = "빈 응답";
                    }
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                ObjectNode node = MAPPER.createObjectNode();
                node.put("caseId", item[0]);
                node.put("condition", item[1]);
                node.put("rep", Integer.parseInt(item[2]));
                node.put("backend", analyzer.backend());
                node.put("seconds", Math.round((System.nanoTime() - t0) / 100_000_000.0) / 10.0);
                node.put("prompt", p);
                node.put("response", response);
                if (error == null) {
                    node.putNull("error");
                } else {
                    node.put("error", error);
                }
                synchronized (REPEAT_PATH) {
                    try (BufferedWriter w = Files.newBufferedWriter(REPEAT_PATH, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                        w.write(MAPPER.writeValueAsString(node));
                        w.newLine();
                    }
                }
                System.out.println("[E2'] " + finished.incrementAndGet() + "/" + order.size() + " " + item[0] + "/" + item[1]
                        + "/" + item[2] + (error == null ? " 응답 " + response.length() + "자" : " 오류: " + error));
                return null;
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
    }

    /** 계획의 작은따옴표 문자열에서 값은 지우고 앞뒤 % 자리만 남긴다: '%@gmail.com' -> '%?', 'PAID' -> '?' */
    static String maskPlanKeepWildcards(String plan) {
        StringBuilder out = new StringBuilder(plan.length());
        int i = 0;
        int n = plan.length();
        while (i < n) {
            char c = plan.charAt(i);
            if (c != '\'') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            boolean closed = false;
            while (j < n) {
                char d = plan.charAt(j);
                if (d == '\\' && j + 1 < n) {
                    j += 2;
                    continue;
                }
                if (d == '\'') {
                    if (j + 1 < n && plan.charAt(j + 1) == '\'') {
                        j += 2;
                        continue;
                    }
                    closed = true;
                    break;
                }
                j++;
            }
            String body = plan.substring(i + 1, closed ? j : n);
            String lead = body.startsWith("%") ? "%" : "";
            String trail = body.length() > 1 && body.endsWith("%") ? "%" : "";
            out.append('\'').append(lead).append('?').append(trail).append('\'');
            i = closed ? j + 1 : n;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 가림 수준 3단계 (#100)

    private static final Path LEVEL_CASES_PATH =
            Path.of("src", "test", "resources", "experiments", "ai-masking-level-cases.json");
    private static final Path LEVEL_PATH = Path.of("docs", "experiments", "ai-masking-levels.jsonl");

    /**
     * 가림 수준 셋(NONE·STRUCTURE·FULL)을 값이 진단을 가르는 사례 15개와 대조군 3개로 잰다.
     *
     * <p>조건 A~E가 "무엇을 어디까지 가릴 수 있나"를 찾는 과정이었다면, 여기서는 그 답을 제품 설정
     * 세 개로 굳혀 놓고 <b>노출과 진단력의 교환</b>을 다시 잰다. STRUCTURE는 값을 지우되 {@code %} 자리·
     * 자릿수·날짜 거리를 남기고, FULL은 전부 {@code ?}로 만든다.
     *
     * <p>프롬프트에는 제품과 같이 대상 테이블 행수가 붙는다({@link TableScale}) — 선택도는 비율이라
     * 분모가 있어야 판단할 수 있고, 그 분모가 없어서 날짜 사례가 되살아나지 않았다.
     *
     * <p>판정은 이 테스트가 하지 않는다. 응답 원문을 남기고 사람과 별도 모델이 대조한다.
     * 게이트: DBTOWER_EXPERIMENT=1 과 DBTOWER_EXPERIMENT_LEVELS=1
     */
    @Test
    @Order(4)
    void 가림_수준별_노출과_진단력을_18개_사례로_잰다() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("DBTOWER_EXPERIMENT_LEVELS")),
                "DBTOWER_EXPERIMENT_LEVELS=1 일 때만 부른다");
        List<MaskCase> cases = loadCases(LEVEL_CASES_PATH);
        assertEquals(18, cases.size(), "사례는 15개 + 대조군 3개여야 한다");
        AiAnalyzer analyzer = new AiAnalyzer(new SimpleMeterRegistry(), MODEL, RULES_PATH, MAX_TOKENS, EFFORT);
        if (!analyzer.isEnabled()) {
            fail("AI 백엔드가 OFF다 — 빈 응답을 진단력 0으로 세지 않으려 여기서 멈춘다");
        }

        Map<String, Map<String, String>> prompts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> exposure = new LinkedHashMap<>();
        for (MaskCase c : cases) {
            DbmsType type = typeOf(c);
            Db db = dbFor(c.dbms());
            String plan = db.op().explain(c.sql());
            List<String> findings = new RuleBasedAnalyzer().analyze(type, plan);
            String scale = TableScale.describe(db.op(), c.sql());
            Map<String, String> perLevel = new LinkedHashMap<>();
            Map<String, Integer> perLevelExposure = new LinkedHashMap<>();
            for (AiMaskLevel level : AiMaskLevel.values()) {
                boolean mask = level != AiMaskLevel.NONE;
                String sqlForPrompt = new QueryMasker(true, mask).applyForAiPrompt(c.sql());
                String planForPrompt = PlanMasker.maskPlan(type, plan, level, Clock.systemDefaultZone());
                String p = prompt(type, sqlForPrompt, planForPrompt, findings, scale);
                perLevel.put(level.name(), p);
                perLevelExposure.put(level.name(), countExposed(p, c.sensitiveLiterals()));
            }
            prompts.put(c.id(), perLevel);
            exposure.put(c.id(), perLevelExposure);
        }

        // 이미 기록된 (사례, 수준, 회차)는 다시 부르지 않는다 — 한도에 걸려 끊겨도 이어서 돈다
        Set<String> done = new TreeSet<>();
        if (Files.exists(LEVEL_PATH)) {
            for (String line : Files.readAllLines(LEVEL_PATH, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    JsonNode n = MAPPER.readTree(line);
                    if (n.path("error").isNull() && !n.path("response").asText("").isEmpty()) {
                        done.add(n.path("caseId").asText() + "|" + n.path("condition").asText()
                                + "|" + n.path("rep").asInt());
                    }
                }
            }
        }
        List<String[]> order = new ArrayList<>();
        for (MaskCase c : cases) {
            for (AiMaskLevel level : AiMaskLevel.values()) {
                for (int rep = 1; rep <= 3; rep++) {
                    if (!done.contains(c.id() + "|" + level.name() + "|" + rep)) {
                        order.add(new String[]{c.id(), level.name(), String.valueOf(rep)});
                    }
                }
            }
        }
        java.util.Collections.shuffle(order, new Random(SHUFFLE_SEED + 2));
        System.out.println("[#100] 호출 " + order.size() + "회 남음 (18사례 × 3수준 × 3회 = 162)");

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (String[] item : order) {
            futures.add(pool.submit(() -> {
                String p = prompts.get(item[0]).get(item[1]);
                long t0 = System.nanoTime();
                String response = "";
                String error = null;
                try {
                    response = analyzer.analyze(CallSite.EXPLAIN, p).orElse("");
                    if (response.isEmpty()) {
                        error = "빈 응답";
                    }
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                ObjectNode node = MAPPER.createObjectNode();
                node.put("caseId", item[0]);
                node.put("condition", item[1]);
                node.put("rep", Integer.parseInt(item[2]));
                node.put("backend", analyzer.backend());
                node.put("sensitiveLeft", exposure.get(item[0]).get(item[1]));
                node.put("seconds", Math.round((System.nanoTime() - t0) / 100_000_000.0) / 10.0);
                node.put("prompt", p);
                node.put("response", response);
                if (error == null) {
                    node.putNull("error");
                } else {
                    node.put("error", error);
                }
                synchronized (LEVEL_PATH) {
                    try (BufferedWriter w = Files.newBufferedWriter(LEVEL_PATH, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                        w.write(MAPPER.writeValueAsString(node));
                        w.newLine();
                    }
                }
                System.out.println("[#100] " + finished.incrementAndGet() + "/" + order.size() + " "
                        + item[0] + "/" + item[1] + "#" + item[2] + " 노출 "
                        + exposure.get(item[0]).get(item[1]) + (error == null ? "" : " 오류 " + error));
                return null;
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        // 노출은 이 테스트가 직접 센다 — 모델 판정과 무관한 사실이라 여기서 못박는다
        int none = exposure.values().stream().mapToInt(m -> m.get("NONE")).sum();
        int structure = exposure.values().stream().mapToInt(m -> m.get("STRUCTURE")).sum();
        int full = exposure.values().stream().mapToInt(m -> m.get("FULL")).sum();
        System.out.println("[#100] 노출 합계 — NONE " + none + " / STRUCTURE " + structure + " / FULL " + full);
        assertTrue(none > 0, "가리지 않은 조건에서 노출이 0이면 사례가 값을 담고 있지 않다는 뜻이다");
        assertEquals(0, structure, "STRUCTURE에서 민감 리터럴이 남았다");
        assertEquals(0, full, "FULL에서 민감 리터럴이 남았다");
    }

    // ---------------------------------------------------------------- 노출 세기

    /** 프롬프트 하나에 남은 민감 리터럴 수 — 부분 문자열 기준이라 과소평가하지 않는 쪽으로 센다. */
    private static int countExposed(String prompt, List<String> literals) {
        int n = 0;
        for (String literal : literals) {
            n += countOccurrences(prompt, literal);
        }
        return n;
    }

    private record Exposure(Map<String, Map<String, Map<String, Integer>>> perCase, Map<String, Integer> totals) {
    }

    private static Exposure exposure(List<MaskCase> cases, Map<String, Map<String, String>> prompts) {
        Map<String, Map<String, Map<String, Integer>>> perCase = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String condition : CONDITIONS) {
            totals.put(condition, 0);
        }
        for (MaskCase c : cases) {
            Map<String, Map<String, Integer>> byCondition = new LinkedHashMap<>();
            for (String condition : CONDITIONS) {
                String text = prompts.get(c.id()).get(condition);
                Map<String, Integer> literals = new LinkedHashMap<>();
                for (String literal : c.sensitiveLiterals()) {
                    int n = countOccurrences(text, literal);
                    literals.put(literal, n);
                    totals.merge(condition, n, Integer::sum);
                }
                byCondition.put(condition, literals);
            }
            perCase.put(c.id(), byCondition);
        }
        return new Exposure(perCase, totals);
    }

    private static int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + 1;
        }
    }

    // ---------------------------------------------------------------- 자동 채점

    private record Scoring(Map<String, Map<String, Boolean>> scores, Map<String, Integer> calls,
                           Map<String, Integer> errors, Map<String, Integer> hits) {

        double rate(String condition, List<String> caseIds) {
            int scored = 0;
            int hit = 0;
            for (String id : caseIds) {
                Boolean s = scores.get(id).get(condition);
                if (s == null) {
                    continue;
                }
                scored++;
                if (s) {
                    hit++;
                }
            }
            return scored == 0 ? Double.NaN : hit * 100.0 / scored;
        }

        int scoredCount(String condition, List<String> caseIds) {
            int scored = 0;
            for (String id : caseIds) {
                if (scores.get(id).get(condition) != null) {
                    scored++;
                }
            }
            return scored;
        }

        String summaryLine() {
            List<String> l = idsWithPrefix(scores().keySet(), "L");
            List<String> c = idsWithPrefix(scores().keySet(), "C");
            StringBuilder sb = new StringBuilder();
            for (String condition : CONDITIONS) {
                sb.append(condition).append(": L=").append(fmtPct(rate(condition, l)))
                        .append("%(").append(scoredCount(condition, l)).append('/').append(l.size()).append(") C=")
                        .append(fmtPct(rate(condition, c))).append("%(").append(scoredCount(condition, c))
                        .append('/').append(c.size()).append(") ");
            }
            return sb.toString();
        }
    }

    /** 사례 묶음 한 줄 — 분모를 묶음 크기에서 가져온다(코드에 8·4를 적어 두니 새 사례가 들어와도 8로 남았다). */
    private static void appendRateRow(StringBuilder sb, String group, Scoring scoring, List<String> ids) {
        sb.append("| **").append(group).append(" 사례 정답률(").append(ids.size()).append(")** |  |  |");
        for (String condition : CONDITIONS) {
            sb.append(" **").append(fmtPct(scoring.rate(condition, ids))).append("%** (")
                    .append(scoring.scoredCount(condition, ids)).append('/').append(ids.size()).append(") |");
        }
        sb.append('\n');
    }

    /**
     * 사례 묶음을 id 접두사로 뽑는다 — 목록을 코드에 박아 두면 새 사례를 넣어도 채점·문서에서 조용히 빠진다
     * (L9~L11을 넣은 회차에서 실제로 L을 8개만 셌다).
     */
    private static List<String> idsWithPrefix(java.util.Collection<String> ids, String prefix) {
        return ids.stream().filter(id -> id.startsWith(prefix)).sorted(java.util.Comparator
                .comparingInt(id -> Integer.parseInt(id.substring(prefix.length())))).toList();
    }

    private static String fmtPct(double v) {
        return Double.isNaN(v) ? "X" : String.format(Locale.ROOT, "%.0f", v);
    }

    /** 응답에 acceptKeywords가 하나라도 있으면 1. 오류·빈 응답은 채점하지 않는다(null). */
    private static Scoring scoring(List<MaskCase> cases, List<JsonNode> recorded) {
        Map<String, String> responses = new LinkedHashMap<>();
        Map<String, Integer> calls = new LinkedHashMap<>();
        Map<String, Integer> errors = new LinkedHashMap<>();
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String condition : CONDITIONS) {
            calls.put(condition, 0);
            errors.put(condition, 0);
            hits.put(condition, 0);
        }
        for (JsonNode node : recorded) {
            String condition = node.path("condition").asText();
            String caseId = node.path("caseId").asText();
            calls.merge(condition, 1, Integer::sum);
            boolean failed = !node.path("error").isNull() && !node.path("error").asText().isEmpty();
            if (failed) {
                errors.merge(condition, 1, Integer::sum);
            }
            responses.put(caseId + "|" + condition, node.path("response").asText(""));
        }
        Map<String, Map<String, Boolean>> scores = new LinkedHashMap<>();
        for (MaskCase c : cases) {
            Map<String, Boolean> byCondition = new LinkedHashMap<>();
            for (String condition : CONDITIONS) {
                String id = c.id() + "|" + condition;
                String response = responses.getOrDefault(id, "");
                if (!calls.containsKey(condition) || response.isBlank()) {
                    byCondition.put(condition, null);
                    continue;
                }
                boolean hit = accepted(response, c);
                byCondition.put(condition, hit);
                if (hit) {
                    hits.merge(condition, 1, Integer::sum);
                }
            }
            scores.put(c.id(), byCondition);
        }
        return new Scoring(scores, calls, errors, hits);
    }

    private static boolean accepted(String response, MaskCase c) {
        String lower = response.toLowerCase(Locale.ROOT);
        return c.acceptKeywords().stream().anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
    }

    // ---------------------------------------------------------------- 결과 문서

    private static String document(List<DbEnv> envs, List<MaskCase> cases, Map<String, String> plans, Exposure exposure,
                                   Scoring scoring, List<JsonNode> recorded, String startedAt, String mode) {
        List<String> caseIds = cases.stream().map(MaskCase::id).toList();
        List<String> l = idsWithPrefix(caseIds, "L");
        List<String> ctl = idsWithPrefix(caseIds, "C");
        StringBuilder sb = new StringBuilder();
        sb.append("# E2: 마스킹 토글이 가리는 것과 AI 진단 정확도\n\n");
        sb.append("이 문서와 `ai-masking-responses.jsonl`은 `AiMaskingTradeoffExperimentIT`가 실제 DB의 실행계획과 "
                + "실제 모델 응답으로 생성한 결과다 — 숫자도 채점도 손으로 고치지 않는다. 실행: "
                + "`DBTOWER_EXPERIMENT=1 ./gradlew cleanTest test --tests '*AiMaskingTradeoffExperimentIT'`\n\n");
        sb.append("조건은 셋이다. A = SQL 원문 + 계획 원문(제품 기본값), B = SQL만 `?` 마스킹 + 계획 원문(토글을 켠 실제 동작), "
                + "C = SQL 마스킹 + 계획의 작은따옴표 문자열만 `'?'`\n\n");

        sb.append("## 실행 환경\n\n");
        sb.append("| 기종 | 버전 | exp_mask_customers | exp_mask_orders |\n|---|---|---|---|\n");
        for (DbEnv e : envs) {
            sb.append("| ").append(e.dbms()).append(" | ").append(e.version()).append(" | ")
                    .append(e.customers()).append("행(").append(e.gradeDist()).append(") | ")
                    .append(e.orders()).append("행(").append(e.statusDist()).append(") |\n");
        }
        sb.append("\n- 실행 일시: ").append(startedAt).append('\n');
        sb.append("- 모델: 설정값 `").append(MODEL).append("` (CLI 모드는 제품이 --model을 넘기지 않으므로 실제 모델은 CLI 기본값), "
                + "effort=").append(EFFORT).append(", max_tokens=").append(MAX_TOKENS).append(", 백엔드=").append(mode).append('\n');
        sb.append("- 호출: ").append(recorded.size()).append("회 = 사례 15 × 조건 3, 조건 비율 ");
        for (String condition : CONDITIONS) {
            sb.append(condition).append(' ').append(scoring.calls().get(condition)).append("회 ");
        }
        sb.append('\n');
        sb.append("- 오류(정확도 계산에서 제외): ");
        for (String condition : CONDITIONS) {
            sb.append(condition).append(' ').append(scoring.errors().get(condition)).append("건 ");
        }
        sb.append('\n');
        sb.append("- 사례 세트: `").append(CASES_PATH).append("` (L=리터럴이 진단을 가르는 사례 11개 — 날짜·숫자 범위 크기와 날짜 함수 포함, C=리터럴과 무관한 대조군 4개)\n");
        sb.append("- 프롬프트는 제품 `AiAnalysisRunner.run`과 글자 단위로 같다(`프롬프트가_제품_AiAnalysisRunner와_글자_단위로_같다` 테스트가 사례 15개 × 조건 A·B에서 확인)\n");
        sb.append("- 노출 수는 프롬프트 문자열에서 민감 리터럴이 나온 횟수(부분 문자열 기준)다. 숫자 리터럴은 다른 수의 일부로도 세어질 수 있다(예: `1012345678`은 `01012345678` 안에서도 잡힌다)\n\n");

        sb.append("## 표 1: 민감 리터럴 노출 수 (프롬프트 안에서 나온 횟수)\n\n");
        sb.append("| 사례 | 민감 리터럴 | A (SQL 원문) | B (SQL 마스킹) | C (SQL+계획 마스킹) |\n|---|---|---|---|---|\n");
        for (MaskCase mc : cases) {
            Map<String, Map<String, Integer>> byCondition = exposure.perCase().get(mc.id());
            sb.append("| ").append(mc.id()).append(" | `").append(String.join("`, `", mc.sensitiveLiterals())).append("` | ");
            for (String condition : CONDITIONS) {
                int sum = byCondition.get(condition).values().stream().mapToInt(Integer::intValue).sum();
                sb.append(sum).append(" | ");
            }
            sb.append('\n');
        }
        sb.append("| **합계** |  | ");
        for (String condition : CONDITIONS) {
            sb.append("**").append(exposure.totals().get(condition)).append("** | ");
        }
        sb.append("\n\n사례별 내역(리터럴 × 조건)\n\n");
        sb.append("| 사례 | 리터럴 | A | B | C |\n|---|---|---|---|---|\n");
        for (MaskCase mc : cases) {
            Map<String, Map<String, Integer>> byCondition = exposure.perCase().get(mc.id());
            for (String literal : mc.sensitiveLiterals()) {
                sb.append("| ").append(mc.id()).append(" | `").append(literal).append("` | ")
                        .append(byCondition.get("A").get(literal)).append(" | ")
                        .append(byCondition.get("B").get(literal)).append(" | ")
                        .append(byCondition.get("C").get(literal)).append(" |\n");
            }
        }

        sb.append("\n## 표 2: 자동 채점 (acceptKeywords 중 하나라도 포함하면 정답)\n\n");
        sb.append("| 사례 | 기종 | 정답 원인(판단 기준 문서) | A | B | C |\n|---|---|---|---|---|---|\n");
        for (MaskCase mc : cases) {
            sb.append("| ").append(mc.id()).append(" | ").append(mc.dbms()).append(" | ").append(mc.expectedCause()).append(" | ");
            for (String condition : CONDITIONS) {
                sb.append(mark(scoring.scores().get(mc.id()).get(condition))).append(" | ");
            }
            sb.append('\n');
        }
        appendRateRow(sb, "L", scoring, l);
        appendRateRow(sb, "C", scoring, ctl);
        sb.append("\n- `O`=정답, `X`=오답, `-`=오류·빈 응답이라 채점 제외. 위 정답률은 채점한 회차만 분모로 센다(괄호 안이 그 수)\n");
        sb.append("- **자동 채점**이다 — 키워드 포함 여부만 본다. 최종 정확도는 사람 검토 후 확정한다(부록)\n");

        sb.append("\n## 계획에 리터럴이 찍혔는가 (사례별 원문 발췌)\n\n");
        for (MaskCase mc : cases) {
            String plan = plans.get(mc.id()).replaceAll("\\s+", " ").strip();
            StringBuilder found = new StringBuilder();
            for (String literal : mc.sensitiveLiterals()) {
                if (plan.contains(literal)) {
                    found.append(found.length() > 0 ? ", " : "").append('`').append(literal).append('`');
                }
            }
            String excerpt = plan.contains(mc.sensitiveLiterals().get(0))
                    ? around(plan, mc.sensitiveLiterals().get(0)) : first(plan, 160);
            sb.append("- **").append(mc.id()).append("** (").append(mc.dbms()).append(") 계획에 있는 민감 리터럴: ")
                    .append(found.length() == 0 ? "없음" : found).append("\n  - `").append(excerpt.replace("|", "\\|")).append("`\n");
        }

        sb.append("\n## 부록: 응답 앞 300자 (사람 검토용)\n\n");
        Map<String, String> responses = new LinkedHashMap<>();
        for (JsonNode node : recorded) {
            responses.put(node.path("caseId").asText() + "|" + node.path("condition").asText(), node.path("response").asText(""));
        }
        for (MaskCase mc : cases) {
            sb.append("### ").append(mc.id()).append(" — ").append(mc.expectedCause()).append("\n\n");
            for (String condition : CONDITIONS) {
                String response = responses.getOrDefault(mc.id() + "|" + condition, "");
                Boolean score = scoring.scores().get(mc.id()).get(condition);
                sb.append("- **").append(condition).append("** (자동 채점 ").append(score == null ? "제외" : (score ? "정답" : "오답"))
                        .append("): ").append(response.isBlank() ? "(응답 없음)" : first(response.replaceAll("\\s+", " ").strip(), 300))
                        .append('\n');
            }
            sb.append('\n');
        }

        sb.append("## 한계\n\n");
        sb.append("- 표 2·3은 사례 15개, 조건당 1회 호출이다. 같은 조건이라도 호출마다 응답이 달라질 수 있는데 이 실험은 그 분산을 재지 않는다\n");
        sb.append("- 자동 채점은 키워드 포함 여부만 본다. 키워드를 우연히 포함한 오답도 정답으로, 표현이 다른 정답도 오답으로 셀 수 있다\n");
        sb.append("- CLI 모드(claude CLI headless)로만 쟀다. API 경로와 프롬프트·판단 기준은 같지만 모델·샘플링은 다를 수 있고, "
                + "CLI 모드에서 제품은 --model을 넘기지 않는다\n");
        sb.append("- 조건 C는 실험 장치다 — 제품에 있는 기능이 아니라, 계획까지 가렸다면 무엇이 남는지 보려고 실행기가 계획 문자열의 "
                + "작은따옴표 리터럴만 바꿔 넣은 것이다. 계획에 작은따옴표로 안 찍힌 값(숫자)은 그대로 남는다\n");
        sb.append("- 노출은 프롬프트 문자열의 부분 문자열 세기다. 계획추정치·비용에 우연히 들어간 숫자도 세어질 수 있다\n");
        sb.append("- 정확도의 차이는 조건 B/C에서 리터럴이 빠져서인지, 계획 문자열이 달라져서인지 이 실험만으로는 가르지 못한다\n");
        sb.append("- 실행계획은 제품과 같은 경로(`DbmsOperator.explain`)로 얻은 원문이다(MySQL `EXPLAIN FORMAT=JSON`, PostgreSQL `EXPLAIN (FORMAT JSON)`)\n");
        return sb.toString();
    }

    private static String mark(Boolean score) {
        return score == null ? "-" : (score ? "O" : "X");
    }

    private static String first(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String around(String text, String needle) {
        int at = text.indexOf(needle);
        if (at < 0) {
            return first(text, 200);
        }
        int from = Math.max(0, at - 90);
        int to = Math.min(text.length(), at + needle.length() + 90);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }

    // ---------------------------------------------------------------- 사례·응답 파일

    private record MaskCase(String id, String dbms, String sql, String expectedCause,
                            List<String> acceptKeywords, List<String> sensitiveLiterals) {
    }

    private static List<MaskCase> loadCases() throws Exception {
        return loadCases(CASES_PATH);
    }

    private static List<MaskCase> loadCases(Path path) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        List<MaskCase> cases = new ArrayList<>();
        for (JsonNode node : root.path("cases")) {
            List<String> keywords = new ArrayList<>();
            node.path("acceptKeywords").forEach(k -> keywords.add(k.asText()));
            List<String> literals = new ArrayList<>();
            node.path("sensitiveLiterals").forEach(k -> literals.add(k.asText()));
            cases.add(new MaskCase(node.path("id").asText(), node.path("dbms").asText(), node.path("sql").asText(),
                    node.path("expectedCause").asText(), keywords, literals));
        }
        return cases;
    }

    private static Set<String> recordedPairs() throws Exception {
        Set<String> pairs = new TreeSet<>();
        for (JsonNode node : readResponses()) {
            pairs.add(node.path("caseId").asText() + "|" + node.path("condition").asText());
        }
        return pairs;
    }

    private static List<JsonNode> readResponses() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.exists(RESPONSES_PATH)) {
            return out;
        }
        for (String line : Files.readAllLines(RESPONSES_PATH, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(MAPPER.readTree(line));
            }
        }
        return out;
    }

    private static void appendResponse(String caseId, String condition, int order, String mode, String backend,
                                       double seconds, String prompt, String response, String error) throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("caseId", caseId);
        node.put("condition", condition);
        node.put("order", order);
        node.put("mode", mode);
        node.put("model", MODEL);
        node.put("seconds", Math.round(seconds * 10) / 10.0);
        node.put("prompt", prompt);
        node.put("response", response);
        if (error == null) {
            node.putNull("error");
        } else {
            node.put("error", error);
        }
        try (BufferedWriter w = Files.newBufferedWriter(RESPONSES_PATH, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(MAPPER.writeValueAsString(node));
            w.newLine();
        }
    }

    /** 계획의 작은따옴표 문자열 리터럴만 '?'로 — 제품 QueryMasker와 같은 이스케이프 규칙(''/백슬래시)을 쓴다. */
    static String maskPlanStringLiterals(String plan) {
        StringBuilder out = new StringBuilder(plan.length());
        int i = 0;
        int n = plan.length();
        while (i < n) {
            char c = plan.charAt(i);
            if (c != '\'') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            boolean closed = false;
            while (j < n) {
                char d = plan.charAt(j);
                if (d == '\\' && j + 1 < n) {
                    j += 2;
                    continue;
                }
                if (d == '\'') {
                    if (j + 1 < n && plan.charAt(j + 1) == '\'') {
                        j += 2;
                        continue;
                    }
                    j++;
                    closed = true;
                    break;
                }
                j++;
            }
            out.append("'?'");
            i = closed ? j : n;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 대상 DB

    private static void prepare(Db db) throws SQLException {
        exec(db, "DROP TABLE IF EXISTS exp_mask_orders", "DROP TABLE IF EXISTS exp_mask_customers",
                "CREATE TABLE exp_mask_customers (id BIGINT PRIMARY KEY, email VARCHAR(100), phone VARCHAR(20),"
                        + " grade VARCHAR(10), created_at DATE)",
                "CREATE TABLE exp_mask_orders (id BIGINT PRIMARY KEY, customer_id BIGINT, status VARCHAR(20),"
                        + " amount INT, created_at DATE)",
                "CREATE INDEX exp_mask_customers_email_idx ON exp_mask_customers (email)",
                "CREATE INDEX exp_mask_customers_phone_idx ON exp_mask_customers (phone)",
                "CREATE INDEX exp_mask_orders_status_idx ON exp_mask_orders (status)",
                "CREATE INDEX exp_mask_orders_amount_created_idx ON exp_mask_orders (amount, created_at)");
        seedCustomers(db);
        seedOrders(db);
        exec(db, db.mysql() ? "ANALYZE TABLE exp_mask_customers" : "ANALYZE exp_mask_customers",
                db.mysql() ? "ANALYZE TABLE exp_mask_orders" : "ANALYZE exp_mask_orders");
        System.out.println("[E2] " + db.dbms() + " exp_mask_* 준비 완료");
    }

    private static void seedCustomers(Db db) throws SQLException {
        try (Connection c = open(db)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO exp_mask_customers (id, email, phone, grade, created_at) VALUES (?, ?, ?, ?, ?)")) {
                for (int i = 1; i <= CUSTOMERS; i++) {
                    ps.setLong(1, i);
                    ps.setString(2, emailOf(i));
                    ps.setString(3, phoneOf(i));
                    ps.setString(4, gradeOf(i));
                    ps.setObject(5, java.time.LocalDate.parse("2024-01-01"));
                    ps.addBatch();
                    if (i % 1000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            c.commit();
        }
    }

    /** L1의 비교 대상값, C1·C3의 조건에 맞는 값이 실제로 있게 한다 */
    private static String emailOf(int i) {
        if (i == 11) {
            return "kimab@exp.test";
        }
        if (i == 7) {
            return "KIM@EXP.TEST";
        }
        return (i % 5 == 0) ? "user" + i + "@gmail.com" : "user" + i + "@exp.test";
    }

    private static String phoneOf(int i) {
        return i == 42 ? "01012345678" : String.format(Locale.ROOT, "010%08d", (i * 7919L) % 100_000_000L);
    }

    private static String gradeOf(int i) {
        int m = i % 100;
        return m < 2 ? "VIP" : (m < 20 ? "GOLD" : "NORMAL");
    }

    private static void seedOrders(Db db) throws SQLException {
        try (Connection c = open(db)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO exp_mask_orders (id, customer_id, status, amount, created_at) VALUES (?, ?, ?, ?, ?)")) {
                for (int i = 1; i <= ORDERS; i++) {
                    ps.setLong(1, i);
                    ps.setLong(2, (i % CUSTOMERS) + 1);
                    ps.setString(3, statusOf(i));
                    ps.setInt(4, (int) ((i * 37L) % 100_000));
                    ps.setObject(5, java.time.LocalDate.parse(dateOf(i)));
                    ps.addBatch();
                    if (i % 1000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            c.commit();
        }
    }

    private static String statusOf(int i) {
        int m = i % 100;
        return m < 95 ? "PAID" : (m < 99 ? "REFUND" : "CANCELLED");
    }

    /** created_at은 2024-01-01 ~ 2026-09-01 사이 — C2(2026-01-01 이후)와 L8(2000-01-01 이후=전 행)이 성립하게 */
    private static String dateOf(int i) {
        int dayOffset = (int) ((i * 13L) % 974);
        return java.time.LocalDate.of(2024, 1, 1).plusDays(dayOffset).toString();
    }

    private static DbEnv dbEnvironment(Db db) throws SQLException {
        try (Connection c = open(db); Statement st = c.createStatement()) {
            String version = queryString(st, db.mysql() ? "SELECT VERSION()" : "SELECT version()");
            long customers = queryLong(st, "SELECT COUNT(*) FROM exp_mask_customers");
            long orders = queryLong(st, "SELECT COUNT(*) FROM exp_mask_orders");
            String grade = distribution(st, "SELECT grade, COUNT(*) FROM exp_mask_customers GROUP BY grade");
            String status = distribution(st, "SELECT status, COUNT(*) FROM exp_mask_orders GROUP BY status");
            return new DbEnv(db.dbms(), version, customers, orders, grade, status);
        }
    }

    private static String distribution(Statement st, String sql) throws SQLException {
        List<String> parts = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                parts.add(rs.getString(1) + " " + rs.getLong(2));
            }
        }
        return String.join(", ", parts);
    }

    private static List<DbEnv> dbEnvironments() {
        List<DbEnv> out = new ArrayList<>();
        for (Db db : DBS) {
            try {
                out.add(dbEnvironment(db));
            } catch (SQLException e) {
                out.add(new DbEnv(db.dbms(), "조회 실패: " + e.getMessage(), -1, -1, "-", "-"));
            }
        }
        return out;
    }

    private record DbEnv(String dbms, String version, long customers, long orders, String gradeDist, String statusDist) {
    }

    private record Db(String dbms, DatabaseInstance instance, ConsoleCredential cred, String url, DbmsOperator op) {
        boolean mysql() {
            return "MySQL".equals(dbms);
        }
    }

    private static Db dbFor(String dbms) {
        return DBS.stream().filter(d -> d.dbms().equals(dbms)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("기종 없음: " + dbms));
    }

    private static DbmsType typeOf(MaskCase c) {
        return DbmsType.valueOf(c.dbms().toUpperCase(Locale.ROOT));
    }

    private static DatabaseInstance instance(long id, DbmsType type, int port, String dbName, String user, String password) {
        DatabaseInstance instance = new DatabaseInstance("e2-" + id, type, "127.0.0.1", port, dbName, user, password);
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    private static Connection open(Db db) throws SQLException {
        return DriverManager.getConnection(db.url(), db.cred().username(), db.cred().password());
    }

    private static void exec(Db db, String... statements) throws SQLException {
        try (Connection c = open(db); Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }

    private static long queryLong(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static String queryString(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
