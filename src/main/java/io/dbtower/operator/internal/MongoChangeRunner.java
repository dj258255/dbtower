package io.dbtower.operator.internal;

import com.mongodb.MongoException;
import com.mongodb.TransactionOptions;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.dbtower.operator.ChangeCommitUncertainException;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.ChangeOutcome;
import io.dbtower.operator.model.ChangeOutcome.Probe;
import io.dbtower.operator.model.ChangePlan;
import io.dbtower.operator.model.ChangePlan.Kind;
import io.dbtower.operator.model.RevertPlan;
import io.dbtower.operator.model.RevertPlan.Conflict;
import io.dbtower.operator.model.RowImage;
import io.dbtower.operator.model.RowImage.ImageColumn;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB 승인 변경의 실행 흐름 — JDBC 계열의 {@link JdbcChangeRunner}와 같은 불변식을 문서 단위로 지킨다.
 *
 * <p>단일 노드 복제셋의 다중 문서 트랜잭션 안에서 명령의 조건으로 변경 전 문서 사본을 잡고, 서버가 보고한 영향 수(n)가 사본 수와
 * 같을 때만 커밋한다. MongoDB에는 행 락이 없고 대신 트랜잭션이 쓰기 충돌(WriteConflict)을 감지해 동시 변경을 끊는다.
 * 기종 제약 두 가지를 그대로 따른다(실측): 트랜잭션 안에서는 explain이 거부되고(OperationNotSupportedInTransaction),
 * 기존 컬렉션의 인덱스 생성도 거부된다. 그래서 실행계획 측정은 트랜잭션 밖에서 하고, DDL은 드라이런하지 않는다.
 *
 * <p>되돌리기 원본은 문서 전체의 canonical Extended JSON이다({@link RowImage#RAW_DOCUMENT_COLUMN}). 화면용 열 값은 사람이 읽기 좋게
 * 펼치되 타입을 잃으므로(정수와 실수, 날짜와 문자열) 되돌리기와 드리프트 대조는 원본 JSON만 쓴다.
 */
final class MongoChangeRunner {

    private static final JsonWriterSettings CANONICAL = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final String ID = "_id";
    private static final int PROBE_RUNS = 3;
    private static final int PROBE_DOC_CAP = 1_000;

    private final MongoClient client;
    private final MongoDatabase db;

    MongoChangeRunner(MongoClient client, String dbName) {
        this.client = client;
        this.db = client.getDatabase(dbName);
    }

    ChangeOutcome execute(ChangePlan plan) {
        long start = System.nanoTime();
        Document command = Document.parse(plan.statement());
        String name = command.keySet().iterator().next();
        String collection = command.getString(name);
        if (plan.kind() == Kind.DDL) {
            if (plan.dryRun()) {
                throw new OperatorException("MongoDB는 기존 컬렉션의 인덱스 생성 같은 DDL을 트랜잭션 안에서 실행하지 않아 드라이런이 곧 실제 실행이 된다. 드라이런하지 않았다");
            }
            Probe before = plan.probeSql() == null ? null : probe(plan.probeSql());
            Document result = db.runCommand(command);
            Probe after = plan.probeSql() == null ? null : probe(plan.probeSql());
            return new ChangeOutcome(true, affected(result), null, null,
                    "DDL은 문서 사본으로 되돌리지 않는다. 역변경은 새 티켓으로 올린다", before, after, millis(start));
        }
        // 트랜잭션 안에서는 explain이 거부된다 — 변경 전 측정은 트랜잭션을 열기 전에 한다
        Probe probeBefore = plan.probeSql() == null ? null : probe(plan.probeSql());
        MongoCollection<Document> target = db.getCollection(collection);
        try (ClientSession session = client.startSession()) {
            session.startTransaction(TransactionOptions.builder()
                    .maxCommitTime((long) Math.max(1, plan.timeoutSeconds()), TimeUnit.SECONDS).build());
            boolean finished = false;
            try {
                List<Document> before = null;
                List<Document> after = null;
                String unavailable = null;
                long affected;
                switch (plan.kind()) {
                    case UPDATE, DELETE -> {
                        boolean update = plan.kind() == Kind.UPDATE;
                        Document spec = command.getList(update ? "updates" : "deletes", Document.class).get(0);
                        Document filter = spec.get("q", Document.class);
                        boolean many = update ? spec.getBoolean("multi", false) : spec.get("limit", Number.class) == null
                                || ((Number) spec.get("limit")).intValue() == 0;
                        int fetch = many ? plan.maxRows() + 1 : 2;
                        before = target.find(session, filter).limit(fetch).into(new ArrayList<>());
                        if (!many && before.size() > 1) {
                            throw new OperatorException("한 문서만 바꾸는 명령인데 조건에 문서가 둘 이상 걸려 어느 문서가 바뀔지 확정할 수 없다."
                                    + " _id로 좁히거나 여러 문서 변경(multi:true / limit:0)으로 올려라");
                        }
                        if (before.size() > plan.maxRows()) {
                            throw new OperatorException("변경 대상이 사본 상한(" + plan.maxRows() + "문서)을 넘어 실행하지 않았다");
                        }
                        affected = affected(db.runCommand(session, command));
                        if (affected != before.size()) {
                            throw new OperatorException("영향 문서 수(" + affected + ")가 변경 전 사본 문서 수(" + before.size()
                                    + ")와 달라 커밋하지 않았다. 사본을 뜬 뒤 조건에 걸리는 문서가 바뀌었다");
                        }
                        List<Document> found = target.find(session, new Document(ID, new Document("$in", ids(before))))
                                .into(new ArrayList<>());
                        if (update && found.size() != before.size()) {
                            throw new OperatorException("변경 뒤 같은 _id로 찾은 문서 수가 사본과 달라 커밋하지 않았다");
                        }
                        if (!update && !found.isEmpty()) {
                            throw new OperatorException("삭제 뒤에도 같은 _id 문서가 남아 커밋하지 않았다");
                        }
                        after = update ? found : List.of();
                    }
                    case INSERT -> {
                        List<Document> documents = command.getList("documents", Document.class);
                        affected = affected(db.runCommand(session, command));
                        if (documents.stream().allMatch(d -> d.containsKey(ID))) {
                            before = List.of();
                            after = target.find(session, new Document(ID, new Document("$in", ids(documents))))
                                    .into(new ArrayList<>());
                            if (after.size() != affected) {
                                unavailable = "넣은 _id로 찾은 문서 수가 영향 수와 다르다";
                                before = null;
                                after = null;
                            }
                        } else {
                            unavailable = "_id 없이 넣은 문서는 서버가 키를 붙여 돌려주지 않아 되돌릴 문서를 짚을 수 없다. 문서에 _id를 넣어 올려라";
                        }
                    }
                    default -> {
                        affected = affected(db.runCommand(session, command));
                        unavailable = "캡처 없이 실행하기로 사람이 인정한 변경이다";
                    }
                }
                if (plan.dryRun()) {
                    session.abortTransaction();
                    finished = true;
                    return new ChangeOutcome(false, affected, image(before), image(after), unavailable, probeBefore, null, millis(start));
                }
                commit(session);
                finished = true;
                Probe probeAfter = plan.probeSql() == null ? null : probe(plan.probeSql());
                return new ChangeOutcome(true, affected, image(before), image(after), unavailable, probeBefore, probeAfter, millis(start));
            } catch (MongoException e) {
                throw new OperatorException("MongoDB 변경 실행 실패: " + e.getMessage(), e);
            } finally {
                if (!finished && session.hasActiveTransaction()) {
                    abortQuietly(session);
                }
            }
        }
    }

    RevertPlan.Outcome revert(RevertPlan plan) {
        long start = System.nanoTime();
        RowImage reference = plan.originalKind() == Kind.DELETE ? plan.before() : plan.after();
        if (reference == null || reference.indexOf(RowImage.RAW_DOCUMENT_COLUMN) < 0) {
            throw new OperatorException("되돌릴 문서 사본이 없다");
        }
        MongoCollection<Document> target = db.getCollection(plan.table());
        List<Document> referenceDocs = documents(reference);
        try (ClientSession session = client.startSession()) {
            session.startTransaction(TransactionOptions.builder()
                    .maxCommitTime((long) Math.max(1, plan.timeoutSeconds()), TimeUnit.SECONDS).build());
            boolean finished = false;
            try {
                Map<String, Document> current = new HashMap<>();
                for (Document d : target.find(session, new Document(ID, new Document("$in", ids(referenceDocs)))).into(new ArrayList<>())) {
                    current.put(idKey(d), d);
                }
                List<Conflict> conflicts = new ArrayList<>();
                for (Document ref : referenceDocs) {
                    Document now = current.get(idKey(ref));
                    List<String> key = List.of(display(ref.get(ID)));
                    if (plan.originalKind() == Kind.DELETE) {
                        if (now != null) {
                            conflicts.add(new Conflict(key, List.of(), "삭제했던 _id의 문서가 다시 생겼다"));
                        }
                    } else if (now == null) {
                        conflicts.add(new Conflict(key, List.of(), "실행 뒤 문서가 사라졌다"));
                    } else if (!now.toJson(CANONICAL).equals(ref.toJson(CANONICAL))) {
                        conflicts.add(new Conflict(key, changedFields(ref, now), "실행 뒤 값이 바뀌었다"));
                    }
                }
                if (!conflicts.isEmpty()) {
                    session.abortTransaction();
                    finished = true;
                    return new RevertPlan.Outcome(false, 0, conflicts, millis(start));
                }
                long restored = 0;
                switch (plan.originalKind()) {
                    case UPDATE -> {
                        for (Document b : documents(plan.before())) {
                            restored += requireOne(target.replaceOne(session, new Document(ID, b.get(ID)), b).getMatchedCount());
                        }
                    }
                    case INSERT -> {
                        for (Document a : referenceDocs) {
                            restored += requireOne(target.deleteOne(session, new Document(ID, a.get(ID))).getDeletedCount());
                        }
                    }
                    case DELETE -> {
                        for (Document b : referenceDocs) {
                            target.insertOne(session, b);
                            restored++;
                        }
                    }
                    default -> throw new OperatorException(plan.originalKind() + "은 문서 사본으로 되돌리지 않는다");
                }
                if (plan.dryRun()) {
                    session.abortTransaction();
                    finished = true;
                    return new RevertPlan.Outcome(false, restored, List.of(), millis(start));
                }
                commit(session);
                finished = true;
                return new RevertPlan.Outcome(true, restored, List.of(), millis(start));
            } catch (MongoException e) {
                throw new OperatorException("MongoDB 되돌리기 실패: " + e.getMessage(), e);
            } finally {
                if (!finished && session.hasActiveTransaction()) {
                    abortQuietly(session);
                }
            }
        }
    }

    /** 실행계획(queryPlanner.winningPlan)과 반복 실행 응답시간 — 트랜잭션 밖에서만 잰다 */
    private Probe probe(String commandJson) {
        try {
            Document command = Document.parse(commandJson);
            Document explained = db.runCommand(new Document("explain", Document.parse(commandJson)).append("verbosity", "queryPlanner"));
            Object winning = explained.get("queryPlanner", Document.class) == null ? explained
                    : explained.get("queryPlanner", Document.class).get("winningPlan");
            String plan = winning instanceof Document d ? d.toJson(JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build())
                    : String.valueOf(winning);
            if ("find".equals(command.keySet().iterator().next())) {
                command.putIfAbsent("limit", PROBE_DOC_CAP);
            }
            List<Long> timings = new ArrayList<>(PROBE_RUNS);
            for (int run = 0; run < PROBE_RUNS; run++) {
                long t0 = System.nanoTime();
                db.runCommand(command);
                timings.add((System.nanoTime() - t0) / 1_000);
            }
            return new Probe(plan, timings, null);
        } catch (RuntimeException e) {
            return new Probe(null, List.of(), e.getMessage());
        }
    }

    /** 화면용으로 펼친 열 + 되돌리기 원본 열. 열은 등장한 최상위 키의 합집합(문서마다 모양이 달라도 된다) */
    private static RowImage image(List<Document> docs) {
        if (docs == null) {
            return null;
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(ID);
        docs.forEach(d -> keys.addAll(d.keySet()));
        List<ImageColumn> columns = new ArrayList<>();
        keys.forEach(k -> columns.add(new ImageColumn(k, Types.OTHER, "bson")));
        columns.add(new ImageColumn(RowImage.RAW_DOCUMENT_COLUMN, Types.OTHER, "bson"));
        List<List<String>> rows = new ArrayList<>(docs.size());
        for (Document d : docs) {
            List<String> row = new ArrayList<>(columns.size());
            for (String k : keys) {
                row.add(d.containsKey(k) ? display(d.get(k)) : null);
            }
            row.add(d.toJson(CANONICAL));
            rows.add(row);
        }
        return new RowImage(columns, List.of(ID), rows);
    }

    private static List<Document> documents(RowImage image) {
        int raw = image.indexOf(RowImage.RAW_DOCUMENT_COLUMN);
        return image.rows().stream().map(r -> Document.parse(r.get(raw))).toList();
    }

    private static List<Object> ids(List<Document> docs) {
        return docs.stream().map(d -> d.get(ID)).toList();
    }

    private static String idKey(Document d) {
        return new Document(ID, d.get(ID)).toJson(CANONICAL);
    }

    private static List<String> changedFields(Document ref, Document now) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(ref.keySet());
        keys.addAll(now.keySet());
        List<String> changed = new ArrayList<>();
        for (String k : keys) {
            String a = ref.containsKey(k) ? new Document("v", ref.get(k)).toJson(CANONICAL) : null;
            String b = now.containsKey(k) ? new Document("v", now.get(k)).toJson(CANONICAL) : null;
            if (!Objects.equals(a, b)) {
                changed.add(k);
            }
        }
        return changed;
    }

    /** 사람이 읽을 값 — 문자열·숫자는 그대로, 나머지는 relaxed JSON. 되돌리기에는 쓰지 않는다 */
    static String display(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof org.bson.types.ObjectId id) {
            return id.toHexString();
        }
        String json = new Document("v", v).toJson(RELAXED);
        return json.substring(json.indexOf(':') + 1, json.length() - 1).strip();
    }

    private static long affected(Document result) {
        Object n = result.get("n");
        return n instanceof Number number ? number.longValue() : 0;
    }

    private static long requireOne(long count) {
        if (count != 1) {
            throw new OperatorException("되돌리기 명령 하나가 " + count + "문서에 닿아 커밋하지 않았다(_id 하나에 정확히 1문서여야 한다)");
        }
        return 1;
    }

    private static void commit(ClientSession session) {
        try {
            session.commitTransaction();
        } catch (MongoException e) {
            if (e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                throw new ChangeCommitUncertainException("커밋 결과를 알 수 없다(서버 응답 유실). 대상 문서를 직접 확인해야 한다: "
                        + e.getMessage(), e);
            }
            throw e;
        }
    }

    private static void abortQuietly(ClientSession session) {
        try {
            session.abortTransaction();
        } catch (RuntimeException ignored) {
            // 세션이 이미 깨졌다면 서버가 트랜잭션 수명 만료로 정리한다 — 원래 예외를 덮지 않는다
        }
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
