package io.dbtower.operator.internal;

import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E7c — 온라인 변경: 문서 백필의 낡은 사본과 따라잡기(MongoDB). #168
 *
 * <p>스키마가 없는 문서 DB에서 "온라인 변경"은 전 문서 백필로 나타난다. RDB(E7 · E7b)의
 * 낡은 사본 문제가 여기서는 <b>백필이 읽고 쓰는 사이에 writer가 끼어드는 lost update</b>로 나타나고,
 * 캡처는 binlog · 트리거 대신 <b>Change Streams</b>(oplog, replica set 요구)가 맡는다.
 *
 * <p>세 arm을 같은 동시 쓰기 부하에서 돌린다.
 * <ul>
 *   <li>REPLACE — 읽은 문서를 변환해 무조건 replace. 끼어든 쓰기를 덮는다(lost update 실측용 대조군)</li>
 *   <li>CONDITIONAL — 읽은 버전이 그대로일 때만 갱신(조건부). 덮지 않는 대신 건너뛴 문서가 구형식으로 남는다</li>
 *   <li>CONDITIONAL_STREAM — 조건부 + 백필 시작부터 Change Streams로 바뀐 문서를 모아 뒤에서 따라잡는다</li>
 * </ul>
 *
 * <p>유실 판정 기준(사전): 최종 값이 {마지막 writer 값, 'v2:'+그 값} 어디에도 없으면 유실.
 * 잔존은 끝났을 때 'v2:' 접두가 없는 문서 수 — 변환이 닿지 못한 범위다.
 *
 * <p>실행: {@code DBTOWER_EXPERIMENT=1 ./gradlew test --tests '*OnlineBackfillMongoExperimentIT'}
 * 전용 컨테이너(단일 replica set, Change Streams 전제)를 먼저 세운다:
 * <pre>
 * docker run -d --name e7-mongo --tmpfs /data/db -p 27027:27017 mongo:7 --replSet rs0
 * docker exec e7-mongo mongosh --quiet --eval "rs.initiate()"
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_EXPERIMENT", matches = "1")
class OnlineBackfillMongoExperimentIT {

    private static final Path DOC = Path.of("docs", "experiments", "online-backfill-mongo.md");
    private static final Path CSV = Path.of("docs", "experiments", "online-backfill-mongo.csv");

    private static final String URI = "mongodb://127.0.0.1:27027/?directConnection=true";
    private static final String DB = "sample";
    private static final String COLLECTION = "swap_scale";

    /** 스모크는 DBTOWER_E7_ROWS로 줄인다. 본 측정 기본값은 1,000,000 */
    private static final int DOCS = Integer.parseInt(System.getenv().getOrDefault("DBTOWER_E7_ROWS", "1000000"));
    private static final int BATCH_DOCS = 1_000;
    private static final int WRITERS = 2;
    private static final int SAMPLE_IDS = 200;
    private static final long SAMPLE_INTERVAL_MS = 50;
    private static final int TIMEOUT_SECONDS = 300;

    private record Run(String mode, long totalMillis, long catchupMillis, long mixedExposureMillis,
                       long lostDocs, long untransformedDocs, long skippedByCondition,
                       double writerP50Ms, double writerP95Ms, double writerMaxMs, long writerCommits) {
    }

    private final List<Run> runs = new ArrayList<>();

    @Test
    void 백필_세_방식을_같은_쓰기_부하에서_잰다() throws Exception {
        String startedAt = OffsetDateTime.now().toString();
        try (MongoClient client = MongoClients.create(URI)) {
            for (String mode : List.of("REPLACE", "CONDITIONAL", "CONDITIONAL_STREAM")) {
                seed(client);
                runs.add(measure(client, mode));
                System.out.println("[E7c] " + runs.getLast());
            }
        }
        write(startedAt);
        assertEquals(0, runs.get(1).lostDocs(), "CONDITIONAL: 조건부 갱신은 writer 값을 덮으면 안 된다(사전 기준)");
        assertEquals(0, runs.get(2).lostDocs(), "CONDITIONAL_STREAM: 유실이 없어야 한다(사전 기준)");
    }

    private void seed(MongoClient client) {
        MongoCollection<Document> col = client.getDatabase(DB).getCollection(COLLECTION);
        col.drop();
        List<Document> buf = new ArrayList<>(10_000);
        for (long id = 1; id <= DOCS; id++) {
            buf.add(new Document("_id", id).append("note", "n" + id).append("ver", 0L));
            if (buf.size() == 10_000) {
                col.insertMany(buf);
                buf.clear();
            }
        }
        if (!buf.isEmpty()) {
            col.insertMany(buf);
        }
    }

    private Run measure(MongoClient client, String mode) throws Exception {
        MongoCollection<Document> col = client.getDatabase(DB).getCollection(COLLECTION);
        AtomicBoolean stop = new AtomicBoolean(false);
        Map<Long, java.util.Set<String>> writesById = new ConcurrentHashMap<>();
        List<Double> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong insertSeq = new AtomicLong(DOCS + 1);

        List<Thread> writers = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(WRITERS);
        for (int w = 0; w < WRITERS; w++) {
            Thread t = new Thread(() -> writerLoop(col, stop, ready, writesById, latencies, insertSeq),
                    "e7c-writer-" + w);
            t.start();
            writers.add(t);
        }
        ready.await(30, TimeUnit.SECONDS);

        MixedSampler sampler = new MixedSampler(col);
        Thread samplerThread = new Thread(sampler, "e7c-sampler");
        samplerThread.start();

        StreamTail tail = null;
        if ("CONDITIONAL_STREAM".equals(mode)) {
            tail = new StreamTail(col);
            tail.start();
        }

        long started = System.nanoTime();
        long skipped = backfill(col, mode);
        long catchupMillis = 0;
        if (tail != null) {
            long c0 = System.nanoTime();
            tail.stopAndAwait();
            catchup(col, tail.touchedIds);
            catchupMillis = (System.nanoTime() - c0) / 1_000_000;
        }
        long totalMillis = (System.nanoTime() - started) / 1_000_000;

        stop.set(true);
        for (Thread t : writers) {
            t.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
        sampler.stop.set(true);
        samplerThread.join(TimeUnit.SECONDS.toMillis(30));

        Verify v = verify(col, writesById);
        double[] pct = percentiles(latencies);
        return new Run(mode, totalMillis, catchupMillis, sampler.mixedExposureMillis(),
                v.lost(), v.untransformed(), skipped, pct[0], pct[1], pct[2], latencies.size());
    }

    private void writerLoop(MongoCollection<Document> col, AtomicBoolean stop, CountDownLatch ready,
                            Map<Long, java.util.Set<String>> writesById, List<Double> latencies, AtomicLong insertSeq) {
        Random random = new Random();
        ready.countDown();
        long seq = 0;
        while (!stop.get()) {
            long t0 = System.nanoTime();
            String note = "w:" + Thread.currentThread().getName() + ":" + (seq++);
            if (random.nextInt(10) == 0) {
                long id = insertSeq.getAndIncrement();
                col.insertOne(new Document("_id", id).append("note", note).append("ver", 0L));
                writesById.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(note);
            } else {
                long id = 1 + random.nextLong(DOCS);
                var result = col.updateOne(Filters.eq("_id", id),
                        Updates.combine(Updates.set("note", note), Updates.inc("ver", 1L)));
                if (result.getModifiedCount() == 1) {
                    writesById.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(note);
                }
            }
            latencies.add((System.nanoTime() - t0) / 1_000_000.0);
        }
    }

    /** `_id` 커서 배치 백필. REPLACE는 무조건 덮고, CONDITIONAL은 읽은 버전일 때만 갱신한다 */
    private long backfill(MongoCollection<Document> col, String mode) {
        boolean conditional = !"REPLACE".equals(mode);
        long skipped = 0;
        long lastId = 0;
        while (true) {
            List<Document> batch = col.find(Filters.gt("_id", lastId))
                    .sort(new Document("_id", 1)).limit(BATCH_DOCS).into(new ArrayList<>());
            if (batch.isEmpty()) {
                break;
            }
            List<WriteModel<Document>> ops = new ArrayList<>(batch.size());
            for (Document d : batch) {
                long id = d.getLong("_id");
                String note = d.getString("note");
                long ver = d.getLong("ver");
                if (note.startsWith("v2:")) {
                    continue;
                }
                if (conditional) {
                    ops.add(new UpdateOneModel<>(
                            Filters.and(Filters.eq("_id", id), Filters.eq("ver", ver)),
                            Updates.combine(Updates.set("note", "v2:" + note), Updates.inc("ver", 1L))));
                } else {
                    ops.add(new ReplaceOneModel<>(Filters.eq("_id", id),
                            new Document("_id", id).append("note", "v2:" + note).append("ver", ver + 1)));
                }
            }
            if (!ops.isEmpty()) {
                var result = col.bulkWrite(ops);
                skipped += ops.size() - result.getModifiedCount();
            }
            lastId = batch.getLast().getLong("_id");
            if (lastId >= DOCS) {
                break; // 백필 범위는 시작 시점 데이터까지. 그 뒤 INSERT는 캡처(스트림) 몫이다
            }
        }
        return skipped;
    }

    /** Change Streams로 백필 중 바뀐 문서 id를 모은다 — oplog 기반 캡처 */
    private final class StreamTail {
        private final MongoCollection<Document> col;
        private final Set<Long> touchedIds = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean stop = new AtomicBoolean(false);
        private Thread thread;

        private StreamTail(MongoCollection<Document> col) {
            this.col = col;
        }

        private void start() throws InterruptedException {
            CountDownLatch opened = new CountDownLatch(1);
            thread = new Thread(() -> {
                ChangeStreamIterable<Document> stream = col.watch()
                        .fullDocument(FullDocument.UPDATE_LOOKUP)
                        .maxAwaitTime(200, TimeUnit.MILLISECONDS);
                try (MongoCursor<ChangeStreamDocument<Document>> cursor = stream.cursor()) {
                    opened.countDown();
                    while (!stop.get()) {
                        ChangeStreamDocument<Document> event = cursor.tryNext();
                        if (event != null && event.getDocumentKey() != null) {
                            touchedIds.add(event.getDocumentKey().getInt64("_id").getValue());
                        }
                    }
                }
            }, "e7c-stream");
            thread.start();
            opened.await(30, TimeUnit.SECONDS);
        }

        private void stopAndAwait() throws InterruptedException {
            stop.set(true);
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }
    }

    /** 스트림이 모은 id를 조건부로 재변환한다. writer와의 경합은 재시도 한 바퀴로 흡수한다 */
    private void catchup(MongoCollection<Document> col, Set<Long> ids) {
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Long> remaining = new ArrayList<>();
            for (Long id : ids) {
                Document d = col.find(Filters.eq("_id", id)).first();
                if (d == null) {
                    continue;
                }
                String note = d.getString("note");
                if (note.startsWith("v2:")) {
                    continue;
                }
                var result = col.updateOne(
                        Filters.and(Filters.eq("_id", id), Filters.eq("ver", d.getLong("ver"))),
                        Updates.combine(Updates.set("note", "v2:" + note), Updates.inc("ver", 1L)));
                if (result.getModifiedCount() == 0) {
                    remaining.add(id);
                }
            }
            if (remaining.isEmpty()) {
                return;
            }
            ids = ConcurrentHashMap.newKeySet();
            ids.addAll(remaining);
        }
    }

    private final class MixedSampler implements Runnable {
        private final MongoCollection<Document> col;
        private final AtomicBoolean stop = new AtomicBoolean(false);
        private volatile long firstMixedNanos = -1;
        private volatile long lastMixedNanos = -1;

        private MixedSampler(MongoCollection<Document> col) {
            this.col = col;
        }

        @Override
        public void run() {
            long step = Math.max(1, (long) DOCS / SAMPLE_IDS);
            List<Long> ids = new ArrayList<>();
            for (long id = 1; id <= DOCS; id += step) {
                ids.add(id);
            }
            try {
                while (!stop.get()) {
                    long transformed = col.countDocuments(Filters.and(
                            Filters.in("_id", ids), Filters.regex("note", "^v2:")));
                    long total = col.countDocuments(Filters.in("_id", ids));
                    if (transformed > 0 && transformed < total) {
                        long now = System.nanoTime();
                        if (firstMixedNanos < 0) {
                            firstMixedNanos = now;
                        }
                        lastMixedNanos = now;
                    }
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException("샘플러 실패", e);
            }
        }

        private long mixedExposureMillis() {
            return firstMixedNanos < 0 ? 0 : (lastMixedNanos - firstMixedNanos) / 1_000_000;
        }
    }

    private record Verify(long lost, long untransformed) {
    }

    private Verify verify(MongoCollection<Document> col, Map<Long, java.util.Set<String>> writesById) {
        long lost = 0;
        for (Map.Entry<Long, java.util.Set<String>> e : writesById.entrySet()) {
            Document d = col.find(Filters.eq("_id", e.getKey())).first();
            if (d == null) {
                lost++;
                continue;
            }
            String note = d.getString("note");
            // 유실 = 그 문서에 쓴 어떤 값에서도 유도되지 않는 최종 값. 커밋 순서는 장부로 못 가려 집합 기준
            if (!e.getValue().contains(note)
                    && (!note.startsWith("v2:") || !e.getValue().contains(note.substring(3)))) {
                lost++;
            }
        }
        long untransformed = col.countDocuments(Filters.not(Filters.regex("note", "^v2:")));
        return new Verify(lost, untransformed);
    }

    private static double[] percentiles(List<Double> latencies) {
        if (latencies.isEmpty()) {
            return new double[]{0, 0, 0};
        }
        List<Double> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        return new double[]{
                sorted.get(sorted.size() / 2),
                sorted.get((int) Math.min(sorted.size() - 1L, Math.round(sorted.size() * 0.95))),
                sorted.getLast()};
    }

    private void write(String startedAt) throws Exception {
        StringBuilder csv = new StringBuilder("mode,total_ms,catchup_ms,mixed_exposure_ms,lost,"
                + "untransformed,skipped_by_condition,writer_p50_ms,writer_p95_ms,writer_max_ms,writer_commits\n");
        StringBuilder md = new StringBuilder();
        md.append("# E7c: 온라인 변경 — 문서 백필의 낡은 사본과 따라잡기 (MongoDB)\n\n");
        md.append("이 문서와 `online-backfill-mongo.csv`는 `OnlineBackfillMongoExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.\n\n");
        md.append("- 실행 일시: ").append(startedAt).append('\n');
        md.append("- 대상: 전용 MongoDB 7 단일 replica set(27027, tmpfs) — Change Streams 전제\n");
        md.append("- 변경: `swap_scale` ").append(String.format(Locale.ROOT, "%,d", DOCS))
                .append("문서의 note에 'v2:' 접두를 `_id` 커서 ").append(BATCH_DOCS).append("건 배치로 백필\n");
        md.append("- 동시 writer ").append(WRITERS).append("개: 임의 문서 갱신 90%(ver 증가) + 새 문서 10%\n");
        md.append("- 유실 판정(사전): 최종 값이 {마지막 writer 값, 'v2:'+그 값} 어디에도 없으면 유실. "
                + "잔존은 종료 시점에 v2 접두가 없는 문서 수(백필 범위 밖 신규 문서 포함)\n");
        md.append("- 백필 범위는 시작 시점 `_id`까지다. 그 뒤 INSERT를 변환 대상으로 삼을지는 캡처(스트림)의 몫이고, "
                + "스트림 없는 arm의 잔존에는 그 신규 문서가 들어 있다\n\n");
        md.append("| 방식 | 총 소요 | 따라잡기 | 혼합 노출 | 유실 | 잔존 | 조건 불일치 skip "
                + "| writer p50 | p95 | max | 커밋 수 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Run r : runs) {
            csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%d%n",
                    r.mode(), r.totalMillis(), r.catchupMillis(), r.mixedExposureMillis(), r.lostDocs(),
                    r.untransformedDocs(), r.skippedByCondition(), r.writerP50Ms(), r.writerP95Ms(),
                    r.writerMaxMs(), r.writerCommits()));
            md.append(String.format(Locale.ROOT,
                    "| %s | %.1f초 | %.1f초 | %.1f초 | %d | %d | %d | %.2fms | %.2fms | %.0fms | %d |%n",
                    r.mode(), r.totalMillis() / 1000.0, r.catchupMillis() / 1000.0,
                    r.mixedExposureMillis() / 1000.0, r.lostDocs(), r.untransformedDocs(),
                    r.skippedByCondition(), r.writerP50Ms(), r.writerP95Ms(), r.writerMaxMs(),
                    r.writerCommits()));
        }
        Files.createDirectories(DOC.getParent());
        Files.writeString(CSV, csv, StandardCharsets.UTF_8);
        Files.writeString(DOC, md, StandardCharsets.UTF_8);
    }
}
