package io.dbtower.operator.internal;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.UpdateResult;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.BulkBatchOutcome;
import io.dbtower.operator.model.BulkChangePlan;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB의 대량 일괄 변경 — {@code _id} 범위로 쪼개 배치마다 따로 쓴다(#128).
 *
 * <p>{@link JdbcBulkChangeRunner}와 같은 불변식을 지킨다: 승인된 filter를 다시 쓰지 않고 {@code $and}로
 * 범위만 덧붙이며, 영향 문서 수가 목표 배치 크기를 넘으면 <b>쓰지 않는다</b>.
 *
 * <p><b>왜 {@code _id} 타입이 하나여야 하는가</b>(실측, docs/bulk-change-spec.md #128 판정): MongoDB의
 * 비교 연산자는 타입 경계를 넘지 않는다. {@code [{_id: 1}, {_id: 2}, {_id: "a"}]}에서 경계 조회가 마지막 키로
 * 숫자 {@code 2}를 주면 다음 구간 {@code {_id: {$gt: 2}}}가 문자 {@code _id}를 하나도 돌려주지 않는다.
 * 배치는 "더 없다"고 보고 정상 종료하고 문자 {@code _id} 문서는 조용히 빠진다 — 오류도 나지 않는 누락이다.
 * 그래서 실행 전에 타입이 하나인지 확인하고, 섞였으면 거부한다.
 *
 * <p>SQL 계열과 다른 점 하나 더: 트랜잭션을 열지 않는다. 배치 하나가 {@code updateMany} 한 번이고
 * 그 자체가 원자적이다(단일 문서 단위가 아니라 명령 단위로 서버가 처리한다). 소량 경로(MongoChangeRunner)는
 * 사본 대조를 위해 다중 문서 트랜잭션을 쓰지만, 이 경로는 사본을 잡지 않으므로 그 비용을 지지 않는다.
 */
final class MongoBulkChangeRunner {

    private final MongoDatabase db;

    MongoBulkChangeRunner(MongoClient client, String dbName) {
        this.db = client.getDatabase(dbName);
    }

    /**
     * 대상 컬렉션의 {@code _id} 타입 목록 — 하나가 아니면 호출자가 거부한다.
     * 전체 문서를 훑으므로 실행 전에 한 번만 부르고 배치마다 다시 보지 않는다.
     */
    List<String> idTypes(String collection) {
        List<String> types = new ArrayList<>();
        db.getCollection(collection).aggregate(List.of(
                        new Document("$group", new Document("_id", new Document("$type", "$_id")))))
                .forEach(d -> types.add(String.valueOf(d.get("_id"))));
        return types;
    }

    /** 다음 배치가 닫을 구간의 상한 {@code _id}. 더 고칠 문서가 없으면 null. */
    Object nextBoundary(BulkChangePlan plan, Object lastKey) {
        try {
            Bson filter = filterFor(plan, lastKey, null);
            List<Object> ids = new ArrayList<>();
            collection(plan).find(filter)
                    .projection(new Document("_id", 1))
                    .sort(Sorts.ascending("_id"))
                    .limit(plan.batchRows())
                    .maxTime(plan.timeoutSeconds(), TimeUnit.SECONDS)
                    .forEach(d -> ids.add(d.get("_id")));
            return ids.isEmpty() ? null : ids.get(ids.size() - 1);
        } catch (RuntimeException e) {
            throw new OperatorException("배치 경계 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 구간 {@code (fromKey, toKey]} 하나를 고친다. 영향 문서 수가 목표를 넘으면 쓰지 않는다.
     *
     * <p>SQL 경로처럼 "쓰고 나서 롤백"할 수 없다 — 트랜잭션을 열지 않으므로 되돌릴 자리가 없다.
     * 그래서 <b>쓰기 전에</b> 같은 조건으로 세어 목표를 넘는지 본다. 세는 사이 문서가 늘면 실제 영향 수가
     * 목표를 넘을 수 있어, 쓴 뒤에도 한 번 더 대조해 넘었으면 예외로 알린다(이미 쓴 것은 남는다 —
     * 이 경로는 부분 적용이 정상 상태다).
     */
    BulkBatchOutcome executeBatch(BulkChangePlan plan, Object fromKey, Object toKey) {
        Bson filter = filterFor(plan, fromKey, toKey);
        MongoCollection<Document> coll = collection(plan);
        try {
            long expected = coll.countDocuments(filter);
            if (expected > plan.batchRows()) {
                throw new OperatorException("배치가 목표 문서 수를 넘겨 쓰지 않았다(목표 " + plan.batchRows()
                        + ", 예상 " + expected + ") — 조건이 예상과 다르다");
            }
            long t0 = System.nanoTime();
            UpdateResult result = coll.updateMany(filter, Document.parse(plan.statementHead()));
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            long affected = result.getModifiedCount();
            if (affected > plan.batchRows()) {
                throw new OperatorException("배치가 목표 문서 수를 넘겼다(목표 " + plan.batchRows()
                        + ", 영향 " + affected + ") — 이미 쓴 것은 남는다. 마지막 키로 어디까지 적용됐는지 확인한다");
            }
            return new BulkBatchOutcome(key(fromKey), key(toKey), affected, elapsed);
        } catch (OperatorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OperatorException("배치 실행 실패: " + e.getMessage(), e);
        }
    }

    /** 승인된 filter를 다시 쓰지 않고 {@code $and}로 범위만 덧붙인다. */
    private static Bson filterFor(BulkChangePlan plan, Object fromKey, Object toKey) {
        List<Bson> parts = new ArrayList<>();
        if (!plan.whereTail().isBlank()) {
            parts.add(Document.parse(plan.whereTail()));
        }
        if (fromKey != null) {
            parts.add(Filters.gt("_id", fromKey));
        }
        if (toKey != null) {
            parts.add(Filters.lte("_id", toKey));
        }
        return parts.isEmpty() ? new Document() : Filters.and(parts);
    }

    private MongoCollection<Document> collection(BulkChangePlan plan) {
        return db.getCollection(plan.table());
    }

    private static List<Object> key(Object value) {
        return value == null ? null : List.of(value);
    }
}
