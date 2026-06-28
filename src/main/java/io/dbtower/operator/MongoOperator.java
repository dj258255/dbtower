package io.dbtower.operator;

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
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

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

    public MongoOperator(DatabaseInstance instance, MongoClientCache clients, BackupTools backupTools) {
        this.instance = instance;
        this.clients = clients;
        this.backupTools = backupTools;
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
                        Accumulators.first("command", "$command")),
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
                            ((Number) doc.getOrDefault("docsExamined", 0)).longValue()));
                }
                return stats;
            });
        } catch (Exception e) {
            throw new OperatorException("MongoDB 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
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
                    result.add(new SlowQuery(
                            queryText(doc.getString("ns"), doc.get("command", Document.class)),
                            ((Number) doc.getOrDefault("millis", 0)).doubleValue(),
                            ((Number) doc.getOrDefault("docsExamined", 0)).longValue(),
                            String.valueOf(doc.get("ts"))));
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
        try {
            return withClient(client -> db(client)
                    .runCommand(new Document("explain", command).append("verbosity", "queryPlanner"))
                    .toJson(PRETTY));
        } catch (Exception e) {
            throw new OperatorException("MongoDB 실행계획 조회 실패: " + e.getMessage(), e);
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
     * mongodump --archive를 stdout으로 받아 파일로 쓴다.
     * 비밀번호는 argv에 싣지 않고 --config /dev/stdin으로 표준입력을 통해 전달한다
     * (mongodump에는 MYSQL_PWD 같은 환경변수가 없어 stdin이 유일한 비노출 경로).
     */
    @Override
    public BackupResult backup(BackupPolicy policy) {
        if (policy.type() == BackupPolicy.BackupType.LOG) {
            throw new UnsupportedOperationException("MongoDB 로그 백업은 oplog 아카이빙으로 별도 구성 필요");
        }
        java.nio.file.Path out = java.nio.file.Path.of(backupTools.backupDir(),
                "mongo-%s-%s.archive".formatted(
                        BackupCommands.safeFileName(instance.getName()), BackupCommands.timestamp()));
        return BackupCommands.run(
                BackupCommands.render(backupTools.mongodumpCommand(), instance),
                java.util.Map.of(),
                out,
                BackupCommands.yamlEntry("password", instance.getPassword()));
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

    private String queryText(String ns, Document command) {
        String text = ns + " " + (command == null ? "{}" : command.toJson());
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }
}
