package io.dbtower.operator.internal;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.operator.model.BulkBatchOutcome;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.testsupport.TargetTableLock;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MongoDB의 대량 일괄 변경 — {@code _id} 범위로 쪼개 누락·중복 0(#128).
 *
 * <p>확인하는 것이 SQL 계열과 하나 더 있다. <b>{@code _id} 타입이 섞인 컬렉션을 거부하는지</b>다.
 * MongoDB의 비교 연산자는 타입 경계를 넘지 않아, 섞인 채로 돌리면 배치가 문서를 조용히 빼먹는다 —
 * 오류도 나지 않는 누락이라 이 경로에서 가장 피해야 하는 모양이다.
 *
 * <p>{@code DBTOWER_CONSOLE_IT=1 ./gradlew test --tests '*MongoBulkChangeIT'}
 */
class MongoBulkChangeIT {

    private static final String GATE = "DBTOWER_CONSOLE_IT";
    private static final String MONGO_URI = "mongodb://root:dbtower1234@127.0.0.1:17017/?authSource=admin";
    private static final ConsoleCredential CRED = new ConsoleCredential("root", "dbtower1234");
    private static final String COLLECTION = "bulk_mongo_it";

    private static final int MATCHING = 50;
    private static final int OTHERS = 20;
    private static final int BATCH = 7;

    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    private static TargetTableLock targetLock;

    @BeforeAll
    static void lockTarget() {
        // Mongo는 자문 락 수단이 없어 잡지 않는다. 다른 실험과 컬렉션 이름이 겹치지 않게 둔다
        targetLock = TargetTableLock.acquireIfEnabled(GATE, List.of());
    }

    @AfterAll
    static void unlockTarget() {
        if (targetLock != null) {
            targetLock.close();
        }
    }

    @AfterEach
    void close() {
        pools.closeAll();
    }

    private static DatabaseInstance instance(long id) {
        DatabaseInstance instance = new DatabaseInstance("mongo-bulk-it", DbmsType.MONGODB, "127.0.0.1", 17017,
                "sample", "root", "dbtower1234");
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("ObjectId 컬렉션을 _id 범위로 훑어 조건에 맞는 문서만 한 번씩 바꾼다")
    void objectIdCollection() {
        seed(true);
        MongoOperator op = new MongoOperator(instance(9401), new MongoClientCache(pools), null, null);
        try {
            BulkChangePlan plan = new BulkChangePlan("{\"$set\": {\"note\": \"done\"}}", "{\"kind\": \"M\"}",
                    COLLECTION, "_id", BATCH, 30);

            assertThat(op.bulkKeyTypes(CRED, COLLECTION)).containsExactly("objectId");

            List<BulkBatchOutcome> batches = new ArrayList<>();
            List<Object> lastKey = null;
            while (true) {
                List<Object> toKey = op.nextBulkBoundary(CRED, plan, lastKey);
                if (toKey == null) {
                    break;
                }
                batches.add(op.executeBulkBatch(CRED, plan, lastKey, toKey));
                lastKey = toKey;
            }

            long changed = count("{\"note\": \"done\"}");
            long outside = count("{\"note\": \"done\", \"kind\": {\"$ne\": \"M\"}}");
            long sum = batches.stream().mapToLong(BulkBatchOutcome::affectedRows).sum();

            assertThat(changed).as("조건에 맞는 문서가 모두 바뀐다(누락 0)").isEqualTo(MATCHING);
            assertThat(outside).as("조건 밖은 건드리지 않는다").isZero();
            assertThat(sum).as("배치 영향 수의 합 = 실제 바뀐 수(중복 0)").isEqualTo(MATCHING);
            assertThat(batches).hasSizeGreaterThan(1);
            for (BulkBatchOutcome b : batches) {
                assertThat(b.affectedRows()).isLessThanOrEqualTo(BATCH);
            }
            assertThat(batches.get(0).fromKey()).isNull();
            for (int i = 1; i < batches.size(); i++) {
                assertThat(batches.get(i).fromKey()).isEqualTo(batches.get(i - 1).toKey());
            }
        } finally {
            drop();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("_id 타입이 섞이면 타입 목록으로 드러나 실행 전에 막을 수 있다")
    void mixedIdTypesAreDetected() {
        seed(false);
        MongoOperator op = new MongoOperator(instance(9402), new MongoClientCache(pools), null, null);
        try {
            // 실행 전 조건이 이 목록으로 거부한다 — 섞인 채 돌리면 문서가 조용히 빠진다
            assertThat(op.bulkKeyTypes(CRED, COLLECTION)).hasSizeGreaterThan(1);

            // 실제로 빠지는 것을 여기서 보인다: 숫자 경계 뒤의 문자 _id를 범위가 잡지 못한다
            BulkChangePlan plan = new BulkChangePlan("{\"$set\": {\"note\": \"done\"}}", "{\"kind\": \"M\"}",
                    COLLECTION, "_id", 100, 30);
            List<Object> first = op.nextBulkBoundary(CRED, plan, null);
            assertThat(first).isNotNull();
            List<Object> next = op.nextBulkBoundary(CRED, plan, first);
            assertThat(next).as("타입 경계를 넘지 못해 '더 없다'로 보인다 — 이것이 조용한 누락이다").isNull();
        } finally {
            drop();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = GATE, matches = "1")
    @DisplayName("구간이 목표 문서 수를 넘으면 쓰지 않는다")
    void refusesOversizedBatch() {
        seed(true);
        MongoOperator op = new MongoOperator(instance(9403), new MongoClientCache(pools), null, null);
        try {
            BulkChangePlan plan = new BulkChangePlan("{\"$set\": {\"note\": \"done\"}}", "{\"kind\": \"M\"}",
                    COLLECTION, "_id", 3, 30);
            // 경계를 목표보다 넓게 직접 준다 — 구간의 문서가 목표를 넘는 상황을 만든다
            List<Object> wide = op.nextBulkBoundary(CRED,
                    new BulkChangePlan(plan.statementHead(), plan.whereTail(), COLLECTION, "_id", 30, 30), null);
            assertThatThrownBy(() -> op.executeBulkBatch(CRED, plan, null, wide))
                    .isInstanceOf(OperatorException.class)
                    .hasMessageContaining("목표 문서 수를 넘겨 쓰지 않았다");
            assertThat(count("{\"note\": \"done\"}")).as("쓰지 않았으니 한 문서도 바뀌지 않았다").isZero();
        } finally {
            drop();
        }
    }

    /** @param singleType true면 _id가 ObjectId 하나, false면 숫자와 문자가 섞인다 */
    private static void seed(boolean singleType) {
        drop();
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            MongoCollection<Document> coll = client.getDatabase("sample").getCollection(COLLECTION);
            List<Document> docs = new ArrayList<>();
            for (int i = 0; i < MATCHING + OTHERS; i++) {
                Object id = singleType ? new ObjectId() : (i % 2 == 0 ? (Object) (long) i : "k" + i);
                docs.add(new Document("_id", id).append("kind", i < MATCHING ? "M" : "X"));
            }
            coll.insertMany(docs);
        }
    }

    private static void drop() {
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            client.getDatabase("sample").getCollection(COLLECTION).drop();
        }
    }

    private static long count(String filterJson) {
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            return client.getDatabase("sample").getCollection(COLLECTION)
                    .countDocuments(Document.parse(filterJson));
        }
    }
}
