package io.dbtower.operator.internal;

import io.dbtower.operator.BackupCommands;
import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.model.DbParameter;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.IndexAdvice;
import io.dbtower.operator.model.IndexSchema;
import io.dbtower.operator.model.IndexUsage;
import io.dbtower.operator.model.LatencyPercentile;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.PartitionInfo;
import io.dbtower.operator.PlanShapes;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.operator.model.ReplicationState;
import io.dbtower.operator.RestoreSupport;
import io.dbtower.operator.model.RestoreVerification;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.operator.model.SlowQuery;
import io.dbtower.operator.model.TableDetail;
import io.dbtower.operator.model.TableSchema;
import io.dbtower.operator.model.TableStat;
import io.dbtower.operator.model.WaitEvent;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.HealthStatus;
import org.bson.Document;
import org.bson.json.JsonWriterSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Comparator;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * MongoDB 어댑터 — 유일한 비 JDBC 구현체.
 *
 * DbmsOperator 인터페이스가 SQL/JDBC를 전제하지 않는다는 증명이 이 클래스의 존재 이유다.
 * AbstractJdbcOperator를 상속하지 않고 인터페이스를 직접 구현하며, 플랫폼의 나머지 코드
 * (스냅샷 폴러, 시점 비교, 회귀 감지, MCP)는 한 줄도 바뀌지 않는다.
 *
 * 통계 소스: system.profile (프로파일러 레벨 2 — 모든 연산 기록)
 * 주의: system.profile은 capped collection이라 성능 스키마류의 무한 누적 카운터가 아니다.
 * 가득 차면 오래된 문서부터 덮어써서 합계가 줄어들 수 있는데, 시점 비교의 음수 클램프가
 * 이 경우를 카운터 리셋과 같은 방식으로 흡수한다.
 */
public class MongoOperator implements DbmsOperator {

    /** explain 허용 명령 — 관리 플랫폼이 임의 쓰기 명령을 실행하면 안 되기 때문 (JDBC 계열의 requireSelect와 같은 원칙) */
    private static final Set<String> EXPLAINABLE = Set.of("find", "aggregate", "count", "distinct");

    private static final JsonWriterSettings PRETTY = JsonWriterSettings.builder().indent(true).build();

    private final DatabaseInstance instance;
    private final MongoClientCache clients;
    private final BackupTools backupTools;
    private final HistogramSnapshotStore histogramStore;

    public MongoOperator(DatabaseInstance instance, MongoClientCache clients, BackupTools backupTools,
                         HistogramSnapshotStore histogramStore) {
        this.instance = instance;
        this.clients = clients;
        this.backupTools = backupTools;
        this.histogramStore = histogramStore;
    }

    /** 캐시 클라이언트는 재사용, 등록 전 검증용 1회 클라이언트는 쓰고 바로 닫는다 */
    private <T> T withClient(Function<MongoClient, T> fn) {
        MongoClient client = clients.get(instance);
        try {
            return fn.apply(client);
        } finally {
            if (clients.isEphemeral(instance)) {
                client.close();
            }
        }
    }

    private MongoDatabase db(MongoClient client) {
        return client.getDatabase(instance.getDbName());
    }

    @Override
    public HealthStatus health() {
        long start = System.currentTimeMillis();
        try {
            return withClient(client -> {
                db(client).runCommand(new Document("ping", 1));
                String version = db(client).runCommand(new Document("buildInfo", 1)).getString("version");
                return HealthStatus.up("MongoDB " + version, System.currentTimeMillis() - start);
            });
        } catch (Exception e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    /**
     * system.profile을 queryHash 단위로 집계 — performance_schema digest 집계와 같은 역할.
     * queryHash가 없는 연산(insert 등)은 op:ns로 묶는다.
     */
    @Override
    public List<QueryStat> queryStats(int limit) {
        List<org.bson.conversions.Bson> pipeline = List.of(
                Aggregates.match(Filters.and(
                        Filters.regex("ns", "^" + Pattern.quote(instance.getDbName()) + "\\."),
                        Filters.not(Filters.regex("ns", "\\.system\\.")))),
                Aggregates.group(
                        new Document("$ifNull", List.of("$queryHash",
                                new Document("$concat", List.of("$op", ":", "$ns")))),
                        Accumulators.sum("calls", 1),
                        Accumulators.sum("totalMs", new Document("$ifNull", List.of("$millis", 0L))),
                        Accumulators.sum("docsExamined", new Document("$ifNull", List.of("$docsExamined", 0L))),
                        Accumulators.first("ns", "$ns"),
                        Accumulators.first("command", "$command"),
                        // BSON 비교에서 null < 문자열이므로 max는 "이 그룹에 planSummary가 하나라도 있으면 그 값".
                        // 구간 내 플랜이 갈렸으면(plan flip) 그중 하나만 보인다 — 대표값이지 최근값이 아님.
                        Accumulators.max("planSummary", "$planSummary")),
                Aggregates.sort(Sorts.descending("totalMs")),
                Aggregates.limit(limit));
        try {
            return withClient(client -> {
                List<QueryStat> stats = new ArrayList<>();
                for (Document doc : db(client).getCollection("system.profile").aggregate(pipeline)) {
                    stats.add(new QueryStat(
                            String.valueOf(doc.get("_id")),
                            queryText(doc.getString("ns"), doc.get("command", Document.class)),
                            ((Number) doc.getOrDefault("calls", 0)).longValue(),
                            ((Number) doc.getOrDefault("totalMs", 0)).doubleValue(),
                            ((Number) doc.getOrDefault("docsExamined", 0)).longValue(),
                            doc.getString("planSummary")));
                }
                return stats;
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 레이턴시 백분위 (D4a) — MySQL이 QUANTILE 컬럼을 주고 PG가 못 주는 것과 달리, MongoDB는
     * system.profile에 op별 <b>원시 millis 샘플</b>을 그대로 남긴다. 그래서 근사가 아니라 우리가
     * 표본을 모아 직접 계산한다(COMPUTED). queryStats와 같은 queryHash 단위로 millis를 모아,
     * 오름차순 정렬 후 nearest-rank로 p95/p99를 뽑는다. 프로파일러 레벨 2(모든 연산 기록) 전제.
     *
     * system.profile은 capped collection이라 오래된 표본은 덮어써진다 — 즉 이 백분위는 자연히
     * "최근 표본 위주"다(MySQL QUANTILE의 무한 누적과 반대 성질). 총 소요시간 상위 그룹부터 담는다.
     */
    @Override
    public List<LatencyPercentile> latencyPercentiles(int limit) {
        List<org.bson.conversions.Bson> pipeline = List.of(
                Aggregates.match(Filters.and(
                        Filters.regex("ns", "^" + Pattern.quote(instance.getDbName()) + "\\."),
                        Filters.not(Filters.regex("ns", "\\.system\\.")))),
                Aggregates.group(
                        new Document("$ifNull", List.of("$queryHash",
                                new Document("$concat", List.of("$op", ":", "$ns")))),
                        Accumulators.push("samples", new Document("$ifNull", List.of("$millis", 0L))),
                        Accumulators.sum("totalMs", new Document("$ifNull", List.of("$millis", 0L))),
                        Accumulators.first("ns", "$ns"),
                        Accumulators.first("command", "$command")),
                Aggregates.sort(Sorts.descending("totalMs")),
                Aggregates.limit(limit));
        try {
            return withClient(client -> {
                List<LatencyPercentile> result = new ArrayList<>();
                // 인스턴스 단위 히스토그램(opLatencies)을 먼저 — 프로파일러가 꺼져 있어도 나오는 값이라
                // COMPUTED가 전멸하는 상황에서 이게 유일한 관측이 된다(B-3의 핵심 가치).
                addInstanceLatencyHistogram(client, result);
                for (Document doc : db(client).getCollection("system.profile").aggregate(pipeline)) {
                    List<Double> samples = new ArrayList<>();
                    for (Object m : doc.getList("samples", Object.class, List.of())) {
                        samples.add(((Number) m).doubleValue());
                    }
                    samples.sort(null); // 오름차순 정렬 후 nearest-rank
                    result.add(new LatencyPercentile(
                            String.valueOf(doc.get("_id")),
                            queryText(doc.getString("ns"), doc.get("command", Document.class)),
                            percentile(samples, 95),
                            percentile(samples, 99),
                            LatencyPercentile.COMPUTED));
                }
                return result;
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 레이턴시 백분위 계산 실패: " + e.getMessage(), e);
        }
    }

    /** opLatencies 히스토그램을 뽑을 연산 축 — reads/writes/commands(트랜잭션은 데모 범위 밖). */
    private static final List<String> OP_LATENCY_CATEGORIES = List.of("reads", "writes", "commands");

    /**
     * 인스턴스 단위 레이턴시 백분위 (B-3, NATIVE_HISTOGRAM) — serverStatus의 opLatencies 히스토그램.
     *
     * <p>MongoDB는 {@code serverStatus.opLatencies}에 reads/writes/commands별로 지수 폭 버킷 히스토그램
     * ({@code {micros: <버킷 하한>, count: <재기동 이후 누적>}})을 항상 기록한다 — <b>프로파일러와 무관</b>하게.
     * 프로파일러가 꺼진 인스턴스에선 system.profile 기반 COMPUTED가 전멸하는데, 이 히스토그램은 살아 있어
     * 인스턴스 층위의 p95를 준다. 버킷이 누적이라 직전 스냅샷과 차분하면 "최근 구간"이 되고, 교차 버킷 안을
     * {@link HistogramPercentile#interpolate}로 선형 보간한다.
     *
     * <p>정직 표기: 이건 <b>쿼리 단위가 아니라 인스턴스(연산 축) 단위</b>이며 버킷 경계 보간이다 — queryText에
     * 그 범위를 밝힌다. 첫 호출/재기동으로 차분이 불가하면 누적 버킷 그대로 보간하고 "누적 — 구간 학습 중" 노트를 단다.
     * serverStatus 접근이 막히면(권한) 조용히 건너뛴다 — 이 축이 없다고 전체 조회가 실패해선 안 된다.
     */
    private void addInstanceLatencyHistogram(MongoClient client, List<LatencyPercentile> result) {
        Document opLat;
        try {
            Document status = db(client).runCommand(new Document("serverStatus", 1)
                    .append("opLatencies", new Document("histograms", true)));
            opLat = status.get("opLatencies", Document.class);
        } catch (RuntimeException e) {
            return; // serverStatus 권한 없음 등 — 인스턴스 축만 생략(COMPUTED는 그대로 진행)
        }
        if (opLat == null) {
            return;
        }
        for (String category : OP_LATENCY_CATEGORIES) {
            Document cat = opLat.get(category, Document.class);
            if (cat == null) {
                continue;
            }
            List<Document> hist = cat.getList("histogram", Document.class, List.of());
            if (hist.isEmpty()) {
                continue; // 이 축에 표본이 아직 없음
            }
            List<Document> sorted = new ArrayList<>(hist);
            sorted.sort((a, b) -> Long.compare(micros(a), micros(b)));
            int n = sorted.size();
            double[] lowerMs = new double[n];
            double[] upperMs = new double[n];
            long[] counts = new long[n];
            for (int i = 0; i < n; i++) {
                lowerMs[i] = micros(sorted.get(i)) / 1000.0;
                counts[i] = ((Number) sorted.get(i).getOrDefault("count", 0L)).longValue();
            }
            for (int i = 0; i < n; i++) {
                upperMs[i] = (i + 1 < n) ? lowerMs[i + 1] : lowerMs[i]; // 마지막 버킷은 상한 미상 — 하한으로 축약
            }
            long[] prev = histogramStore.swap(instance.getId() + ":mongo-oplat:" + category, counts.clone());
            long[] delta = HistogramPercentile.windowDiff(prev, counts);
            long[] use = delta != null ? delta : counts;
            String scope = delta != null ? "최근 구간" : "누적 — 구간 학습 중";
            Double p95 = HistogramPercentile.interpolate(lowerMs, upperMs, use, 0.95);
            if (p95 == null) {
                continue; // 이번 구간 이 축 연산 0회
            }
            Double p99 = HistogramPercentile.interpolate(lowerMs, upperMs, use, 0.99);
            result.add(new LatencyPercentile(
                    "opLatencies:" + category,
                    "인스턴스 " + category + " 레이턴시 (프로파일러 무관, " + scope + ")",
                    round2(p95), p99 == null ? null : round2(p99),
                    LatencyPercentile.NATIVE_HISTOGRAM));
        }
    }

    private static long micros(Document bucket) {
        return ((Number) bucket.getOrDefault("micros", 0L)).longValue();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * nearest-rank 백분위 — 오름차순 정렬된 표본에서 p(0~100)에 해당하는 값. 표본이 없으면 null.
     * rank = ceil(p/100 × n), 인덱스는 rank-1을 [0, n-1]로 클램프. (보간 없는 정직한 정의)
     */
    static Double percentile(List<Double> ascending, double p) {
        int n = ascending.size();
        if (n == 0) {
            return null;
        }
        int rank = (int) Math.ceil(p / 100.0 * n);
        int idx = Math.min(Math.max(rank, 1), n) - 1;
        return ascending.get(idx);
    }

    /**
     * 파티션 조회 (D5) — MongoDB는 UNSUPPORTED. 관계형 파티셔닝(테이블을 경계로 쪼개는 것) 개념이 없다.
     * 데이터 분산은 샤딩(청크·샤드 키)이라는 다른 축이라 여기 섞지 않는다 — 파티션인 척 위장하지 않고
     * 명시적 미지원으로 돌려준다(adviseIndex와 같은 정직성 원칙). 빈 목록이 아니라 사유를 담은 안내 행 하나.
     */
    @Override
    public List<PartitionInfo> partitions(int limit) {
        return List.of(PartitionInfo.unsupported(instance.getType()
                + " 파티션 미지원 — MongoDB에는 관계형 파티셔닝 개념이 없다. "
                + "데이터 분산은 샤딩(청크·샤드 키)이라는 다른 축이며 파티션 조회의 범위 밖."));
    }

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        try {
            return withClient(client -> {
                List<SlowQuery> result = new ArrayList<>();
                var docs = db(client).getCollection("system.profile")
                        .find(Filters.not(Filters.regex("ns", "\\.system\\.")))
                        .sort(Sorts.descending("millis"))
                        .limit(limit);
                for (Document doc : docs) {
                    // system.profile의 planSummary가 인덱스 사용 여부를 그대로 알려준다(예: "IXSCAN { a: 1 }", "COLLSCAN")
                    result.add(new SlowQuery(
                            queryText(doc.getString("ns"), doc.get("command", Document.class)),
                            ((Number) doc.getOrDefault("millis", 0)).doubleValue(),
                            ((Number) doc.getOrDefault("docsExamined", 0)).longValue(),
                            String.valueOf(doc.get("ts")),
                            null, -1, -1,
                            doc.getString("planSummary")));
                }
                return result;
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 슬로우 쿼리 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 입력은 SQL이 아니라 명령 JSON — 예: {"find": "users", "filter": {"name": {"$regex": "user1"}}}
     * 읽기 명령만 explain을 허용하고, 서버의 explain 결과(queryPlanner)를 그대로 돌려준다.
     * COLLSCAN/SORT 스테이지 판정은 RuleBasedAnalyzer가 맡는다.
     */
    @Override
    public String explain(String commandJson) {
        return runExplain(commandJson, "queryPlanner");
    }

    /**
     * 플랜 변경 감지용 shape (plan flip) — Mongo는 계획이 명령 JSON 기반이라 정규화 텍스트로는 explain이
     * 안 된다. system.profile이 저장해 둔 <b>실제 명령</b>을 queryHash로 찾아 explain(queryPlanner)으로
     * 재실행하고, winningPlan의 stage·indexName만 shape로 남긴다. queryId = queryHash.
     *
     * 전제: 프로파일러가 켜져 있어야 샘플이 있다(꺼져 있으면 empty — 우리는 레벨을 바꾸지 않는다).
     * 함정: 프로파일러 command엔 세션·라우팅 메타($db·lsid 등)가 섞여 있어 explain 전에 걷어낸다.
     * 플랜 캐시는 노드별 인메모리라 이력의 단일 출처는 우리 PlanSnapshot이다.
     */
    @Override
    public Optional<String> planShapeForDigest(String queryId, String queryText) {
        try {
            return withClient(client -> {
                Document sample = db(client).getCollection("system.profile")
                        .find(new Document("queryHash", queryId))
                        .sort(new Document("ts", -1)).limit(1).first();
                if (sample == null) {
                    return Optional.<String>empty();
                }
                Document command = sample.get("command", Document.class);
                if (command == null) {
                    return Optional.<String>empty();
                }
                // explain에 부적합한 세션·라우팅 메타 필드 제거
                for (String meta : List.of("$db", "lsid", "$clusterTime", "readConcern",
                        "$readPreference", "apiVersion", "$audit", "mayBypassWriteBlocking")) {
                    command.remove(meta);
                }
                String first = command.keySet().stream().findFirst().orElse("");
                if (!EXPLAINABLE.contains(first)) {
                    return Optional.<String>empty();
                }
                Document explainCmd = new Document("explain", command).append("verbosity", "queryPlanner");
                String json = db(client).runCommand(explainCmd).toJson();
                return Optional.of(PlanShapes.fromMongoPlan(json));
            });
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 실제 실행 계획 (D9) — verbosity를 executionStats로 올려 explain을 돌린다. queryPlanner(추정)와 달리
     * executionStats는 후보 플랜을 <b>실제로 실행</b>해 totalDocsExamined·totalKeysExamined·nReturned를 준다.
     * docsExamined ÷ nReturned(스캔 낭비 비율)로 "인덱스를 못 타 훑는 정도"를 정량화한다(DeepAnalyzer가 판정).
     *
     * 안전: 실제 실행이므로 maxTimeMS를 명령에 실어 실행을 상한한다. 읽기 명령만 허용(EXPLAINABLE — requireSelect와 같은 원칙).
     */
    @Override
    public String explainAnalyze(String commandJson) {
        return runExplain(commandJson, "executionStats");
    }

    /** explain 공통 — 명령 JSON 검증 후 지정 verbosity로 runCommand. executionStats는 실제 실행이라 maxTimeMS로 상한. */
    private String runExplain(String commandJson, String verbosity) {
        Document command;
        try {
            command = Document.parse(commandJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "MongoDB explain 입력은 명령 JSON이어야 합니다 — 예: {\"find\": \"users\", \"filter\": {...}}");
        }
        String first = command.keySet().stream().findFirst().orElse("");
        if (!EXPLAINABLE.contains(first)) {
            throw new IllegalArgumentException("explain은 읽기 명령만 허용합니다: " + EXPLAINABLE);
        }
        Document explainCmd = new Document("explain", command).append("verbosity", verbosity);
        if ("executionStats".equals(verbosity)) {
            explainCmd.append("maxTimeMS", DEEP_DIAGNOSIS_TIMEOUT_MS);
        }
        try {
            return withClient(client -> db(client).runCommand(explainCmd).toJson(PRETTY));
        } catch (Exception e) {
            throw new OperatorException("MongoDB 실행계획 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 인덱스 사용 통계 (D6) — 컬렉션마다 $indexStats를 돌려 인덱스별 accesses.ops(사용 누적)를 읽는다.
     * ops=0이면 통계 리셋(서버 재기동) 이후 미사용 후보. _id_ 기본 인덱스는 PK에 해당하므로 unique로
     * 표시해 후보에서 제외되게 한다. 크기는 $collStats storageStats.indexSizes에서 인덱스명으로 찾는다.
     * 시스템 컬렉션은 제외. 읽기 전용(aggregate 조회만).
     */
    @Override
    public List<IndexUsage> indexUsage(int limit) {
        try {
            return withClient(client -> {
                List<IndexUsage> result = new ArrayList<>();
                for (String name : db(client).listCollectionNames()) {
                    if (name.startsWith("system.")) {
                        continue;
                    }
                    // 인덱스별 크기(bytes) — $collStats storageStats.indexSizes는 인덱스명→크기 맵
                    Document collStats = db(client).getCollection(name)
                            .aggregate(List.of(new Document("$collStats",
                                    new Document("storageStats", new Document()))))
                            .first();
                    Document indexSizes = collStats == null ? new Document()
                            : collStats.get("storageStats", new Document())
                                    .get("indexSizes", new Document());
                    for (Document idx : db(client).getCollection(name)
                            .aggregate(List.of(new Document("$indexStats", new Document())))) {
                        Document accesses = idx.get("accesses", new Document());
                        long ops = ((Number) accesses.getOrDefault("ops", 0L)).longValue();
                        String indexName = idx.getString("name");
                        Number size = (Number) indexSizes.get(indexName);
                        result.add(new IndexUsage(name, indexName, ops,
                                size == null ? null : size.longValue(),
                                "_id_".equals(indexName), // _id_ 기본 인덱스는 PK 취급(후보 제외)
                                IndexUsage.NATIVE));
                    }
                    if (result.size() >= limit) {
                        break;
                    }
                }
                result.sort((a, b) -> Long.compare(
                        a.scanCount() == null ? 0 : a.scanCount(),
                        b.scanCount() == null ? 0 : b.scanCount()));
                return result.size() > limit ? result.subList(0, limit) : result;
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 인덱스 사용 통계 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TableStat> tableStats(int limit) {
        try {
            return withClient(client -> {
                List<TableStat> result = new ArrayList<>();
                for (String name : db(client).listCollectionNames()) {
                    if (name.startsWith("system.")) {
                        continue;
                    }
                    Document stats = db(client).getCollection(name)
                            .aggregate(List.of(new Document("$collStats",
                                    new Document("storageStats", new Document()))))
                            .first();
                    Document storage = stats == null ? new Document()
                            : stats.get("storageStats", new Document());
                    result.add(new TableStat(name,
                            ((Number) storage.getOrDefault("count", 0)).longValue(),
                            ((Number) storage.getOrDefault("size", 0)).longValue(),
                            ((Number) storage.getOrDefault("totalIndexSize", 0)).longValue()));
                }
                result.sort((a, b) -> Long.compare(b.dataBytes(), a.dataBytes()));
                return result.size() > limit ? result.subList(0, limit) : result;
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 컬렉션 통계 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 컬렉션 상세 (D-테이블 상세) — MongoDB는 스키마리스라 CREATE TABLE 같은 네이티브 DDL이 없다.
     * 그래서 "DDL" 자리에 컬렉션 옵션(validator 등)과 인덱스 정의를 JSON으로 담는다(ddlSource=NATIVE지만
     * note로 "테이블 DDL이 아님"을 명시). 기본 통계는 tableStats와 같은 $collStats storageStats에서 뽑고,
     * 인덱스는 listIndexes로 나열한다. 읽기 전용.
     */
    @Override
    public TableDetail tableDetail(String tableName) {
        TableDetailSupport.requireIdentifier(tableName); // 컬렉션명 검증
        try {
            return withClient(client -> {
                // 존재 확인 — 없는 컬렉션은 통계·인덱스가 빈 값으로 나와 오해를 부르므로 명시적 미지원으로
                boolean exists = false;
                for (String n : db(client).listCollectionNames()) {
                    if (n.equals(tableName)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    return TableDetail.unsupported(tableName, "컬렉션을 찾을 수 없습니다: " + tableName);
                }

                // 기본 통계 — tableStats와 동일한 storageStats 소스
                Document stats = db(client).getCollection(tableName)
                        .aggregate(List.of(new Document("$collStats",
                                new Document("storageStats", new Document()))))
                        .first();
                Document storage = stats == null ? new Document()
                        : stats.get("storageStats", new Document());
                long rowCount = ((Number) storage.getOrDefault("count", 0)).longValue();
                long dataBytes = ((Number) storage.getOrDefault("size", 0)).longValue();
                long indexBytes = ((Number) storage.getOrDefault("totalIndexSize", 0)).longValue();
                // avgObjSize는 빈 컬렉션에선 없다 — 지어내지 않고 -1(미상)로 둔다
                long avgRowBytes = storage.get("avgObjSize") instanceof Number avg ? avg.longValue() : -1;
                // wiredTiger 섹션이 있으면 스토리지 엔진을 밝히고, 없으면 null(추정 안 함)
                String engine = storage.get("wiredTiger") != null ? "WiredTiger" : null;

                // 인덱스 — IndexDetail과 "DDL"용 원본 정의를 한 번의 순회에서 함께 모은다
                List<TableDetail.IndexDetail> indexes = new ArrayList<>();
                List<Document> indexDocs = new ArrayList<>();
                for (Document idx : db(client).getCollection(tableName).listIndexes()) {
                    indexDocs.add(idx);
                    Document key = idx.get("key", new Document());
                    boolean unique = idx.getBoolean("unique", false);
                    // 타입은 key 값에서 추론 — 문자열(text/hashed/2dsphere)이면 그 값을, 숫자(1/-1)면 btree
                    String type = "btree";
                    for (Object v : key.values()) {
                        if (v instanceof String s) {
                            type = s;
                            break;
                        }
                    }
                    indexes.add(new TableDetail.IndexDetail(idx.getString("name"),
                            new ArrayList<>(key.keySet()), unique, type,
                            null)); // Mongo는 인덱스별 카디널리티가 없다 → 정직하게 null
                }

                // "DDL" — 스키마리스라 CREATE TABLE이 없으므로 컬렉션 옵션 + 인덱스 정의를 JSON으로 구성
                Document collInfo = db(client).listCollections()
                        .filter(Filters.eq("name", tableName)).first();
                Document options = collInfo == null ? null : collInfo.get("options", Document.class);
                Document ddlDoc = new Document();
                if (options != null && !options.isEmpty()) {
                    ddlDoc.append("options", options); // validator 등 — 비어 있으면 인덱스 정의만 남긴다
                }
                ddlDoc.append("indexes", indexDocs);
                String ddl = ddlDoc.toJson(PRETTY);

                return new TableDetail(tableName, engine, rowCount, dataBytes, indexBytes, avgRowBytes,
                        null, // createdAt — MongoDB는 컬렉션 생성 시각을 쉽게 주지 않는다
                        ddl, TableDetail.DdlSource.NATIVE, indexes,
                        "스키마리스 — 컬렉션 옵션·인덱스 정의(테이블 DDL 아님)");
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 컬렉션 상세 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * mongodump --archive를 stdout으로 받아 파일로 쓴다.
     * 비밀번호는 argv에 싣지 않고 --config /dev/stdin으로 표준입력을 통해 전달한다
     * (mongodump에는 MYSQL_PWD 같은 환경변수가 없어 stdin이 유일한 비노출 경로).
     */
    @Override
    public BackupResult backup(BackupPolicy policy) {
        if (policy.type() == BackupPolicy.BackupType.LOG) {
            return oplogBackup();
        }
        if (policy.type() == BackupPolicy.BackupType.PHYSICAL) {
            throw new UnsupportedOperationException(
                    "MongoDB 물리 백업은 파일시스템 스냅샷/Percona hot backup 영역 — 공식 PITR는 mongodump + oplog 재생이라 물리가 필수는 아니다");
        }
        Path out = Path.of(backupTools.backupDir(),
                "mongo-%s-%s.archive".formatted(
                        BackupCommands.safeFileName(instance.getName()), BackupCommands.timestamp()));
        return BackupCommands.run(
                BackupCommands.render(backupTools.mongodumpCommand(), instance),
                Map.of(),
                out,
                BackupCommands.yamlEntry("password", instance.getPassword()));
    }

    /**
     * 로그 백업 (Phase 2) = local.oplog.rs 덤프. oplog는 복제셋의 변경 로그라 "FULL + oplog"가
     * 시점 복구의 재료다. mongodump --oplog는 --db와 함께 못 쓰므로 oplog 컬렉션을 직접 덤프한다.
     *
     * 게이트(정직): oplog.rs는 복제셋에만 존재한다 — standalone이면 UNSUPPORTED로 사유를 남긴다.
     * 복제셋 전환은 대상 서버 구성이라 우리가 하지 않는다(단일 노드도 replSet 전환으로 사용 가능 안내).
     * 판정은 replicationState()(replSetGetStatus, 에러 76=NoReplicationEnabled)를 재사용한다.
     */
    private BackupResult oplogBackup() {
        if ("STANDALONE".equals(replicationState().role())) {
            throw new UnsupportedOperationException(
                    "standalone — oplog 없음(복제셋 전용). 단일 노드도 replSet 전환(--replSet + initiate)으로 "
                            + "oplog 백업이 가능하지만, 대상 서버 구성이라 우리가 바꾸지 않는다");
        }
        if (backupTools.mongoOplogCommand() == null || backupTools.mongoOplogCommand().isBlank()) {
            throw new UnsupportedOperationException(
                    "oplog 덤프 명령 미설정(dbtower.backup.mongo-oplog-command)");
        }
        Path out = Path.of(backupTools.backupDir(),
                "mongo-oplog-%s-%s.archive".formatted(
                        BackupCommands.safeFileName(instance.getName()), BackupCommands.timestamp()));
        return BackupCommands.run(
                BackupCommands.render(backupTools.mongoOplogCommand(), instance),
                Map.of(),
                out,
                BackupCommands.yamlEntry("password", instance.getPassword()));
    }

    /**
     * Mongo PITR 안내 (Phase 2) — 정석은 FULL 복원 후 oplog를 --oplogReplay + --oplogLimit(ts:ordinal)로
     * 목표 시점까지 재생하는 것(Percona·Pythian의 표준 절차). 생성·안내만, 실행은 사람이 한다.
     */
    @Override
    public String pitrRestoreGuide(String fullLocation, List<String> logLocations, String targetTime) {
        return """
                # MongoDB 시점 복구 안내 (생성된 문안 — 반드시 격리된 복구용 인스턴스에서 실행할 것)
                # 1) 마지막 FULL 아카이브 복원
                mongorestore --archive=%s --drop
                # 2) oplog 아카이브에서 oplog.rs.bson을 추출해 목표 시점까지 재생
                #    (아카이브 %d개 — 시간순. --oplogLimit은 <unix_ts>:<ordinal>, 그 시각 이후 항목은 미적용)
                #    %s
                mongorestore --oplogReplay --oplogFile=<추출한 oplog.rs.bson> --oplogLimit=<%s의 unix_ts>:1
                # 주의: oplog는 순환(capped) 컬렉션 — FULL 이후 구간이 덮어써지기 전에 수집돼 있어야 체인이 유효하다."""
                .formatted(fullLocation, logLocations.size(),
                        String.join("\n                #    ", logLocations), targetTime);
    }

    /**
     * MongoDB 복원 검증 = 아카이브를 격리된 임시 DB로 실제 복원 후 컬렉션 수 확인.
     *
     * mongorestore는 --config /dev/stdin(비밀번호)이 stdin을 쓰므로 아카이브를 stdin으로 줄 수 없다.
     * 그래서 아카이브를 docker cp로 컨테이너에 넣고 --archive=<파일>로 지정한다(비밀번호는 여전히
     * argv 밖). --nsFrom/--nsTo로 원본 네임스페이스를 임시 DB로 리매핑해 원본 컬렉션은 건드리지 않고,
     * 끝나면 임시 DB drop + 컨테이너 임시 파일 rm. 정리 실패해도 원본은 무해.
     */
    @Override
    public RestoreVerification verifyRestore(String location) {
        Path archive = Path.of(location);
        if (!Files.isRegularFile(archive)) {
            return RestoreVerification.failed("아카이브 파일을 찾을 수 없습니다: " + location);
        }
        String target = RestoreSupport.verifyTargetName();
        RestoreSupport.requireSafeName(target);
        List<String> base = BackupCommands.render(backupTools.mongoRestoreCommand(), instance);
        String container = RestoreSupport.dockerContainer(base);
        String inContainerArchive = "/tmp/" + target + ".archive";
        boolean copied = false;
        try {
            RestoreSupport.ExecResult cp = RestoreSupport.exec(
                    List.of("docker", "cp", location, container + ":" + inContainerArchive),
                    Map.of(), null);
            if (!cp.ok()) {
                return RestoreVerification.failed("아카이브 컨테이너 복사 실패: " + cp.errorTail());
            }
            copied = true;
            byte[] config = BackupCommands.yamlEntry("password", instance.getPassword())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            RestoreSupport.ExecResult restore = RestoreSupport.exec(RestoreSupport.concat(base,
                    "--archive=" + inContainerArchive,
                    "--nsFrom=" + instance.getDbName() + ".*",
                    "--nsTo=" + target + ".*"), Map.of(), config);
            if (!restore.ok()) {
                return RestoreVerification.failed("mongorestore 실패: " + restore.errorTail());
            }
            int collections = withClient(client -> {
                int n = 0;
                for (String name : client.getDatabase(target).listCollectionNames()) {
                    if (!name.startsWith("system.")) {
                        n++;
                    }
                }
                return n;
            });
            return RestoreVerification.verified(
                    "임시 DB로 아카이브 복원 성공 (mongorestore, nsTo=" + target + ")", collections);
        } finally {
            // 임시 DB 삭제 — 성공/실패 어느 경로든 격리 대상만 정리(원본 무해)
            try {
                withClient(client -> {
                    client.getDatabase(target).drop();
                    return null;
                });
            } catch (Exception ignored) {
                // 정리 실패는 검증 결과를 뒤집지 않는다 — 임시 대상이라 방치돼도 원본과 무관
            }
            if (copied) {
                RestoreSupport.exec(List.of("docker", "exec", container, "rm", "-f", inContainerArchive),
                        Map.of(), null);
            }
        }
    }

    /**
     * 대기 지표 — MongoDB에는 다른 기종의 wait event에 해당하는 이벤트별 대기 통계가 없다.
     * 대신 serverStatus가 주는 "지금 얼마나 줄 서 있나"를 WaitEvent 형태로 매핑한다:
     *
     * - globalLock.currentQueue.readers/writers: 글로벌 락 큐에서 대기 중인 연산 수 (현재 순간)
     * - wiredTiger.concurrentTransactions.read/write의 out/available: WiredTiger 동시 실행
     *   티켓의 사용/잔여 수 (현재 순간). available이 0이면 새 연산이 티켓을 기다린다 — RDBMS의
     *   커넥션 풀 고갈에 해당하는 병목 신호.
     *
     * 즉 "무엇을 얼마나 기다렸나"(누적)가 아니라 "지금 어디에 줄이 생겼나"(게이지)다.
     * category(QUEUE/TICKET)로 이 차이를 드러내고, totalMs는 시간 정보가 없어 0으로 둔다.
     * 참고: MongoDB 8.0대에서는 concurrentTransactions가 queues.execution으로 이동했다 —
     * 필드가 없으면 큐 지표만 돌려준다 (7.0 컨테이너에서는 존재를 실측 확인, 2026-07-04).
     */
    /**
     * 인덱스 어드바이저 (B3) — MongoDB는 UNSUPPORTED. HypoPG 같은 가상 인덱스 개념이 없어
     * 실제 인덱스 없이 플랜 비용을 시뮬레이션할 표준 수단이 없다. 통과 위장 없이 명시적 미지원.
     */
    @Override
    public IndexAdvice adviseIndex(String sql, String columns) {
        return IndexAdvice.unsupported(instance.getType()
                + " 가상 인덱스 시뮬레이션 미지원 — MongoDB는 HypoPG 같은 가상 인덱스가 없고, "
                + "실제 인덱스를 만든 뒤 explain을 비교해야 하므로 대상 DB를 바꾸지 않는 이 기능의 범위 밖.");
    }

    @Override
    public List<WaitEvent> waitEvents(int limit) {
        try {
            return withClient(client -> {
                Document status = client.getDatabase("admin")
                        .runCommand(new Document("serverStatus", 1));
                List<WaitEvent> result = new ArrayList<>();

                Document queue = section(section(status, "globalLock"), "currentQueue");
                result.add(new WaitEvent("globalLock.currentQueue.readers", "QUEUE(현재 대기 수)",
                        asLong(queue.get("readers")), 0));
                result.add(new WaitEvent("globalLock.currentQueue.writers", "QUEUE(현재 대기 수)",
                        asLong(queue.get("writers")), 0));

                Document tickets = section(section(status, "wiredTiger"), "concurrentTransactions");
                for (String mode : List.of("read", "write")) {
                    Document t = section(tickets, mode);
                    if (t.isEmpty()) {
                        continue;
                    }
                    result.add(new WaitEvent("wiredTiger.concurrentTransactions." + mode + ".out",
                            "TICKET(사용 중)", asLong(t.get("out")), 0));
                    result.add(new WaitEvent("wiredTiger.concurrentTransactions." + mode + ".available",
                            "TICKET(잔여)", asLong(t.get("available")), 0));
                }
                return result.size() > limit ? result.subList(0, limit) : result;
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 대기 지표 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 중첩 Document를 null 안전하게 꺼낸다 — 버전에 따라 없는 섹션은 빈 Document로 */
    private static Document section(Document parent, String key) {
        Document child = parent == null ? null : parent.get(key, Document.class);
        return child == null ? new Document() : child;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 활성 세션 — db.currentOp()의 inprog(active:true). RDBMS의 세션 목록에 대응한다.
     * blockedByPid는 null로 둔다 — MongoDB는 문서/의도 락(intent lock) 모델이라 "A 세션이 B 세션을
     * 막는다"는 관계를 currentOp가 직접 알려주지 않는다. waitingForLock 플래그로 "락을 기다리는 중"은
     * 알 수 있어 waitEvent로 노출하지만, 그 락을 쥔 상대 opid를 표준 명령으로 특정할 수 없어
     * blockedByPid는 정직하게 N/A(null)로 남긴다.
     *
     * pid = opid. 자기 자신(이 currentOp 명령)은 command에 currentOp 키가 있어 걸러낸다.
     * elapsedMs = microsecs_running/1000(없으면 secs_running*1000).
     */
    @Override
    public List<SessionInfo> activeSessions(int limit) {
        try {
            return withClient(client -> {
                Document result = client.getDatabase("admin")
                        .runCommand(new Document("currentOp", 1).append("active", true));
                List<SessionInfo> sessions = new ArrayList<>();
                for (Document op : result.getList("inprog", Document.class, List.of())) {
                    Document command = op.get("command", Document.class);
                    if (command != null && command.containsKey("currentOp")) {
                        continue; // 이 조회 자신은 제외
                    }
                    long opid = asLong(op.get("opid"));
                    double elapsedMs = op.containsKey("microsecs_running")
                            ? asLong(op.get("microsecs_running")) / 1000.0
                            : asLong(op.get("secs_running")) * 1000.0;
                    sessions.add(new SessionInfo(
                            opid,
                            currentOpUser(op),
                            op.getString("op"),
                            Boolean.TRUE.equals(op.getBoolean("waitingForLock")) ? "waitingForLock" : null,
                            null, // 문서 락 모델 — 블로킹 상대 opid를 표준 명령으로 특정 불가
                            queryText(op.getString("ns"), command),
                            elapsedMs));
                    if (sessions.size() >= limit) {
                        break;
                    }
                }
                return sessions;
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 세션 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 세션 종료 — killOp(opid). MongoDB의 killOp에는 취소/강제 구분이 없어(항상 협조적 종료) force는
     * 무시한다. opid는 조회에서 받은 숫자를 그대로 명령 인자로 넣는다(문자열 조립 없음 → 인젝션 무관).
     */
    @Override
    public String killSession(long pid, boolean force) {
        try {
            return withClient(client -> {
                client.getDatabase("admin").runCommand(new Document("killOp", 1).append("op", pid));
                return "killOp(opid=" + pid + ") 실행됨 (force 무시 — MongoDB killOp는 협조적 종료만)";
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 세션 종료 실패: " + e.getMessage(), e);
        }
    }

    /** currentOp의 실행 주체 — 인증이 켜져 있으면 effectiveUsers[0].user, 없으면 null */
    private static String currentOpUser(Document op) {
        List<Document> users = op.getList("effectiveUsers", Document.class, List.of());
        return users.isEmpty() ? null : users.get(0).getString("user");
    }

    @Override
    public ReplicationState replicationState() {
        try {
            return withClient(client -> {
                try {
                    Document status = client.getDatabase("admin")
                            .runCommand(new Document("replSetGetStatus", 1));
                    List<Document> members = status.getList("members", Document.class, List.of());
                    int myState = status.getInteger("myState", -1);
                    String role = switch (myState) {
                        case 1 -> "PRIMARY";
                        case 2 -> "SECONDARY";
                        default -> "STATE_" + myState;
                    };
                    return new ReplicationState(role, -1,
                            "replSet=%s members=%d".formatted(status.getString("set"), members.size()));
                } catch (MongoCommandException e) {
                    if (e.getErrorCode() == 76) { // NoReplicationEnabled
                        return new ReplicationState("STANDALONE", 0, "레플리카셋 미구성");
                    }
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 복제 상태 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 스키마 구조 — MongoDB는 스키마리스라 "컬럼" 개념이 없다. 그래서 컬럼은 항상 비우고
     * 컬렉션 목록과 각 컬렉션의 인덱스(listIndexes)만 담는다 — "왜 저 장비만 다르지"를 인덱스 관점에서
     * 추적하는 용도(예: 운영에만 있는 복합 인덱스). 문서 필드는 문서마다 달라 구조로 확정할 수 없으니
     * 굳이 표본 스캔으로 흉내 내지 않고 정직하게 컬렉션·인덱스 구조만 비교한다.
     *
     * 인덱스의 컬럼은 key 문서의 필드 순서(복합 인덱스 순서 = 삽입 순서)로, unique는 인덱스 옵션에서 읽는다.
     * _id 기본 인덱스도 포함한다(다른 기종의 PK 인덱스와 같은 취급). 시스템 컬렉션은 제외.
     */
    @Override
    public SchemaSnapshot describeSchema() {
        try {
            return withClient(client -> {
                List<TableSchema> tables = new ArrayList<>();
                boolean truncated = false;
                for (String name : db(client).listCollectionNames()) {
                    if (name.startsWith("system.")) {
                        continue;
                    }
                    if (tables.size() >= SchemaSupport.DEFAULT_MAX_TABLES) {
                        truncated = true;
                        break;
                    }
                    List<IndexSchema> indexes = new ArrayList<>();
                    for (Document idx : db(client).getCollection(name).listIndexes()) {
                        Document key = idx.get("key", new Document());
                        boolean unique = Boolean.TRUE.equals(idx.getBoolean("unique"));
                        indexes.add(new IndexSchema(idx.getString("name"),
                                new ArrayList<>(key.keySet()), unique));
                    }
                    // 스키마리스: columns는 빈 리스트(TableSchema 주석 참고)
                    tables.add(new TableSchema(name, List.of(), indexes));
                }
                return new SchemaSnapshot(instance.getType().name(), instance.getDbName(),
                        tables, truncated, SchemaSupport.DEFAULT_MAX_TABLES);
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 스키마 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 파라미터 — db.adminCommand({getParameter:'*'})가 서버 파라미터 전량을 준다.
     * 스칼라(수/문자/불리언)는 그대로 평탄화하고, 중첩(Document/List)은 diff 대상이 아니라
     * 문자열화만 한다(값이 크면 잘라 노이즈 방지). 명령 결과 메타('ok')는 파라미터가 아니라 제외.
     */
    @Override
    public List<DbParameter> parameters() {
        try {
            return withClient(client -> {
                Document params = client.getDatabase("admin")
                        .runCommand(new Document("getParameter", "*"));
                List<DbParameter> result = new ArrayList<>();
                for (Map.Entry<String, Object> e : params.entrySet()) {
                    if ("ok".equals(e.getKey())) {
                        continue;
                    }
                    Object v = e.getValue();
                    String value;
                    if (v instanceof Document || v instanceof List) {
                        String s = String.valueOf(v);
                        value = s.length() > 500 ? s.substring(0, 500) : s; // 중첩은 문자열화(요약)
                    } else {
                        value = String.valueOf(v);
                    }
                    result.add(ParameterSupport.of(e.getKey(), value, null));
                }
                result.sort(Comparator.comparing(DbParameter::name));
                return result;
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 파라미터 조회 실패: " + e.getMessage(), e);
        }
    }

    private String queryText(String ns, Document command) {
        String text = ns + " " + (command == null ? "{}" : command.toJson());
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }
}
