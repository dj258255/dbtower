package io.dbtower.operator.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E7b — 온라인 변경: 배치 커밋 대 ghost 교체(PostgreSQL, 트리거 캡처). #168
 *
 * <p>MySQL(E7)의 캡처가 binlog(비동기, drain 필요)라면 PostgreSQL의 현실적 캡처는
 * pt-osc · pg_repack 계열의 <b>트리거</b>다. 트리거는 동기라 따라잡기가 필요 없는 대신
 * 변경이 도는 동안 <b>모든 writer 커밋이 트리거 비용을 낸다</b>. 그 비용이 writer p95에
 * 얼마로 보이는지가 이 실험의 중심 축이다.
 *
 * <p>arm: BATCH(현행 1,000행 제자리 배치) / SWAP_TRIGGER(트리거 설치 → ghost 변환 복사
 * ON CONFLICT DO NOTHING → 한 트랜잭션 RENAME 교체). 트리거를 먼저 설치하고 복사가 양보하는
 * 순서는 pt-osc와 같다 — 동시 쓰기가 항상 이긴다.
 *
 * <p>logical replication은 적용을 구현하지 않고 <b>비용만 관측</b>한다: 소비자 없는 logical 슬롯을
 * 걸어 두고 각 arm 동안 WAL 적체(current_wal_lsn − restart_lsn)의 최대를 기록한다.
 * 변경 스트림을 켜 두는 것 자체의 값이며, 슬롯이 밀리면 WAL이 쌓인다는 3장의 축과 이어진다.
 *
 * <p>실행: {@code DBTOWER_EXPERIMENT=1 ./gradlew test --tests '*OnlineSwapPostgresExperimentIT'}
 * 전용 컨테이너를 먼저 세운다(wal_level=logical이 필요해 compose의 15432를 쓰지 않는다):
 * <pre>
 * docker run -d --name e7-pg --shm-size=256m --tmpfs /var/lib/postgresql/data \
 *   -p 16434:5432 -e POSTGRES_PASSWORD=dbtower1234 -e POSTGRES_DB=sample \
 *   postgres:16 -c wal_level=logical
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_EXPERIMENT", matches = "1")
class OnlineSwapPostgresExperimentIT {

    private static final Path DOC = Path.of("docs", "experiments", "online-swap-postgres.md");
    private static final Path CSV = Path.of("docs", "experiments", "online-swap-postgres.csv");

    private static final String URL = "jdbc:postgresql://127.0.0.1:16434/sample";
    private static final String USER = "postgres";
    private static final String PASSWORD = "dbtower1234";
    private static final String TABLE = "swap_scale";

    /** 스모크는 DBTOWER_E7_ROWS로 줄인다. 본 측정 기본값은 1,000,000 */
    private static final int ROWS = Integer.parseInt(System.getenv().getOrDefault("DBTOWER_E7_ROWS", "1000000"));
    private static final int BATCH_ROWS = 1_000;
    private static final long PAUSE_MILLIS = 100;
    private static final int WRITERS = 2;
    private static final int SAMPLE_IDS = 200;
    private static final long SAMPLE_INTERVAL_MS = 50;
    private static final int TIMEOUT_SECONDS = 180;
    private static final String SLOT = "e7_cost_probe";

    private record Run(String mode, long totalMillis, long cutoverMillis, long mixedExposureMillis,
                       long lostRows, long missingInserts, long untransformedRows,
                       double writerP50Ms, double writerP95Ms, double writerMaxMs,
                       long writerMaxGapMs, long writerCommits, long extraSpaceBytes,
                       long slotBacklogMaxBytes, long cutoverLeakRows) {
    }

    private final List<Run> runs = new ArrayList<>();

    @Test
    void 배치와_트리거_교체를_같은_쓰기_부하에서_잰다() throws Exception {
        String startedAt = OffsetDateTime.now().toString();
        for (String mode : List.of("BATCH", "SWAP_TRIGGER")) {
            seed();
            runs.add(measure(mode));
            System.out.println("[E7b] " + runs.getLast());
        }
        write(startedAt);
        for (Run r : runs) {
            assertEquals(0, r.lostRows(), r.mode() + ": writer 값이 흔적 없이 사라지면 안 된다(사전 기준)");
            assertEquals(0, r.missingInserts(), r.mode() + ": 트리거는 동기라 신규 행 누락이 없어야 한다");
        }
    }

    private void seed() throws SQLException {
        try (Connection con = open(); Statement st = con.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
            st.execute("DROP TABLE IF EXISTS " + TABLE + "_ghost CASCADE");
            st.execute("DROP TABLE IF EXISTS " + TABLE + "_old CASCADE");
            st.execute("CREATE TABLE " + TABLE + " (id BIGINT PRIMARY KEY, note VARCHAR(80) NOT NULL)");
            st.execute("INSERT INTO " + TABLE + " SELECT g, 'n' || g FROM generate_series(1, " + ROWS + ") g");
        }
    }

    private Run measure(String mode) throws Exception {
        createSlot();
        AtomicBoolean stop = new AtomicBoolean(false);
        Map<Long, java.util.Set<String>> writesById = new ConcurrentHashMap<>();
        List<Long> insertedIds = Collections.synchronizedList(new ArrayList<>());
        List<Double> latencies = Collections.synchronizedList(new ArrayList<>());
        List<Long> commitInstants = Collections.synchronizedList(new ArrayList<>());
        AtomicLong insertSeq = new AtomicLong(ROWS + 1);

        List<Thread> writers = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(WRITERS);
        for (int w = 0; w < WRITERS; w++) {
            Thread t = new Thread(() -> writerLoop(stop, ready, writesById, insertedIds,
                    latencies, commitInstants, insertSeq), "e7b-writer-" + w);
            t.start();
            writers.add(t);
        }
        ready.await(30, TimeUnit.SECONDS);

        MixedSampler sampler = new MixedSampler();
        Thread samplerThread = new Thread(sampler, "e7b-sampler");
        samplerThread.start();
        SlotProbe probe = new SlotProbe();
        Thread probeThread = new Thread(probe, "e7b-slot");
        probeThread.start();

        long started = System.nanoTime();
        long[] cut = "BATCH".equals(mode) ? new long[]{runBatch(), 0} : runSwapTrigger();
        long cutoverMillis = cut[0];
        long cutoverLeakRows = cut[1];
        long totalMillis = (System.nanoTime() - started) / 1_000_000;

        stop.set(true);
        for (Thread t : writers) {
            t.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
        sampler.stop.set(true);
        probe.stop.set(true);
        samplerThread.join(TimeUnit.SECONDS.toMillis(30));
        probeThread.join(TimeUnit.SECONDS.toMillis(30));

        long extraSpace = tableBytes(TABLE + "_old");
        Verify v = verify(writesById, insertedIds);
        double[] pct = percentiles(latencies);
        long maxGap = maxGap(commitInstants);
        try (Connection con = open(); Statement st = con.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE + "_old CASCADE");
        }
        dropSlot();
        return new Run(mode, totalMillis, cutoverMillis, sampler.mixedExposureMillis(),
                v.lost(), v.missingInserts(), v.untransformed(),
                pct[0], pct[1], pct[2], maxGap, latencies.size(), extraSpace,
                probe.maxBacklogBytes, cutoverLeakRows);
    }

    private void writerLoop(AtomicBoolean stop, CountDownLatch ready, Map<Long, java.util.Set<String>> writesById,
                            List<Long> insertedIds, List<Double> latencies, List<Long> commitInstants,
                            AtomicLong insertSeq) {
        Random random = new Random();
        try (Connection con = open()) {
            con.setAutoCommit(false);
            ready.countDown();
            long seq = 0;
            while (!stop.get()) {
                long t0 = System.nanoTime();
                // RENAME 교체 뒤에도 같은 문장이 새 테이블을 보도록 매번 새로 준비한다
                try (PreparedStatement update = con.prepareStatement(
                        "UPDATE " + TABLE + " SET note = ? WHERE id = ?");
                     PreparedStatement insert = con.prepareStatement(
                             "INSERT INTO " + TABLE + " VALUES (?, ?)")) {
                    if (random.nextInt(10) == 0) {
                        long id = insertSeq.getAndIncrement();
                        String note = "w:" + Thread.currentThread().getName() + ":" + (seq++);
                        insert.setLong(1, id);
                        insert.setString(2, note);
                        insert.executeUpdate();
                        con.commit();
                        insertedIds.add(id);
                        writesById.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(note);
                    } else {
                        long id = 1 + random.nextLong(ROWS);
                        String note = "w:" + Thread.currentThread().getName() + ":" + (seq++);
                        update.setString(1, note);
                        update.setLong(2, id);
                        int n = update.executeUpdate();
                        con.commit();
                        if (n == 1) {
                            writesById.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(note);
                        }
                    }
                    latencies.add((System.nanoTime() - t0) / 1_000_000.0);
                    commitInstants.add(System.nanoTime());
                } catch (SQLException e) {
                    try {
                        con.rollback();
                    } catch (SQLException ignored) {
                        // 커넥션이 죽으면 바깥 루프가 끝난다
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("writer 실패", e);
        }
    }

    private long runBatch() throws SQLException, InterruptedException {
        try (Connection con = open()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE " + TABLE + " SET note = 'v2:' || note " +
                    "WHERE id BETWEEN ? AND ? AND note NOT LIKE 'v2:%'")) {
                for (long from = 1; from <= ROWS; from += BATCH_ROWS) {
                    ps.setLong(1, from);
                    ps.setLong(2, Math.min(from + BATCH_ROWS - 1, ROWS));
                    ps.executeUpdate();
                    con.commit();
                    Thread.sleep(PAUSE_MILLIS);
                }
            }
        }
        return 0;
    }

    /**
     * pt-osc 순서: 트리거 먼저, 복사는 양보(ON CONFLICT DO NOTHING), 교체는 한 트랜잭션 RENAME.
     *
     * <p>이름 RENAME의 함정: ACCESS EXCLUSIVE에 막혀 있던 진행 중 문장은 OID에 묶여 있어
     * 잠금이 풀리면 옛 테이블로 들어간다(pg_repack이 이름 대신 relfilenode를 바꾸는 이유).
     * 그래서 컷오버 뒤 옛 테이블로 샌 쓰기를 쓸어 담고 그 수를 유출로 기록한다.
     */
    private long[] runSwapTrigger() throws SQLException, InterruptedException {
        try (Connection con = open(); Statement st = con.createStatement()) {
            st.execute("CREATE TABLE " + TABLE + "_ghost (LIKE " + TABLE + " INCLUDING ALL)");
            st.execute("""
                    CREATE OR REPLACE FUNCTION e7_mirror() RETURNS trigger AS $$
                    BEGIN
                      IF TG_OP = 'DELETE' THEN
                        DELETE FROM swap_scale_ghost WHERE id = OLD.id;
                        RETURN OLD;
                      END IF;
                      INSERT INTO swap_scale_ghost VALUES (NEW.id, NEW.note)
                        ON CONFLICT (id) DO UPDATE SET note = EXCLUDED.note;
                      RETURN NEW;
                    END $$ LANGUAGE plpgsql
                    """);
            st.execute("CREATE TRIGGER e7_mirror_trg AFTER INSERT OR UPDATE OR DELETE ON " + TABLE
                    + " FOR EACH ROW EXECUTE FUNCTION e7_mirror()");
        }
        try (Connection con = open()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO " + TABLE + "_ghost " +
                    "SELECT id, 'v2:' || note FROM " + TABLE + " WHERE id BETWEEN ? AND ? " +
                    "ON CONFLICT (id) DO NOTHING")) {
                for (long from = 1; from <= ROWS; from += BATCH_ROWS) {
                    ps.setLong(1, from);
                    ps.setLong(2, Math.min(from + BATCH_ROWS - 1, ROWS));
                    ps.executeUpdate();
                    con.commit();
                }
            }
        }
        long cutStart = System.nanoTime();
        try (Connection con = open(); Statement st = con.createStatement()) {
            con.setAutoCommit(false);
            st.execute("LOCK TABLE " + TABLE + " IN ACCESS EXCLUSIVE MODE");
            st.execute("DROP TRIGGER e7_mirror_trg ON " + TABLE);
            st.execute("ALTER TABLE " + TABLE + " RENAME TO " + TABLE + "_old");
            st.execute("ALTER TABLE " + TABLE + "_ghost RENAME TO " + TABLE);
            con.commit();
        }
        long cutoverMillis = (System.nanoTime() - cutStart) / 1_000_000;
        Thread.sleep(300); // 잠금에 막혀 있던 문장이 옛 OID로 마저 흘러들 시간
        long leaked = 0;
        try (Connection con = open(); Statement st = con.createStatement()) {
            leaked += st.executeUpdate(
                    "INSERT INTO " + TABLE + " SELECT o.* FROM " + TABLE + "_old o "
                    + "WHERE NOT EXISTS (SELECT 1 FROM " + TABLE + " n WHERE n.id = o.id)");
            leaked += st.executeUpdate(
                    "UPDATE " + TABLE + " n SET note = o.note FROM " + TABLE + "_old o "
                    + "WHERE n.id = o.id AND o.note LIKE 'w:%' "
                    + "AND o.note <> n.note AND 'v2:' || o.note <> n.note");
        }
        return new long[]{cutoverMillis, leaked};
    }

    /** 소비자 없는 logical 슬롯의 WAL 적체를 주기 관측한다 — 캡처 스트림을 켜 두는 비용 */
    private final class SlotProbe implements Runnable {
        private final AtomicBoolean stop = new AtomicBoolean(false);
        private volatile long maxBacklogBytes;

        @Override
        public void run() {
            try (Connection con = open(); Statement st = con.createStatement()) {
                while (!stop.get()) {
                    try (ResultSet rs = st.executeQuery(
                            "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) "
                            + "FROM pg_replication_slots WHERE slot_name = '" + SLOT + "'")) {
                        if (rs.next()) {
                            maxBacklogBytes = Math.max(maxBacklogBytes, rs.getLong(1));
                        }
                    }
                    Thread.sleep(200);
                }
            } catch (SQLException | InterruptedException e) {
                throw new IllegalStateException("슬롯 관측 실패", e);
            }
        }
    }

    private void createSlot() throws SQLException {
        dropSlot();
        try (Connection con = open(); Statement st = con.createStatement()) {
            st.execute("SELECT pg_create_logical_replication_slot('" + SLOT + "', 'test_decoding')");
        }
    }

    private void dropSlot() throws SQLException {
        try (Connection con = open(); Statement st = con.createStatement()) {
            st.execute("SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots "
                    + "WHERE slot_name = '" + SLOT + "'");
        }
    }

    private final class MixedSampler implements Runnable {
        private final AtomicBoolean stop = new AtomicBoolean(false);
        private volatile long firstMixedNanos = -1;
        private volatile long lastMixedNanos = -1;

        @Override
        public void run() {
            long step = Math.max(1, (long) ROWS / SAMPLE_IDS);
            StringBuilder ids = new StringBuilder();
            for (long id = 1; id <= ROWS; id += step) {
                if (!ids.isEmpty()) {
                    ids.append(',');
                }
                ids.append(id);
            }
            String sql = "SELECT COUNT(*) FILTER (WHERE note LIKE 'v2:%'), COUNT(*) FROM " + TABLE
                    + " WHERE id IN (" + ids + ")";
            try (Connection con = open(); Statement st = con.createStatement()) {
                while (!stop.get()) {
                    try (ResultSet rs = st.executeQuery(sql)) {
                        rs.next();
                        long transformed = rs.getLong(1);
                        long total = rs.getLong(2);
                        if (transformed > 0 && transformed < total) {
                            long now = System.nanoTime();
                            if (firstMixedNanos < 0) {
                                firstMixedNanos = now;
                            }
                            lastMixedNanos = now;
                        }
                    } catch (SQLException ignored) {
                        // RENAME 순간의 일시 오류는 다음 주기에 다시 읽는다
                    }
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                }
            } catch (SQLException | InterruptedException e) {
                throw new IllegalStateException("샘플러 실패", e);
            }
        }

        private long mixedExposureMillis() {
            return firstMixedNanos < 0 ? 0 : (lastMixedNanos - firstMixedNanos) / 1_000_000;
        }
    }

    private record Verify(long lost, long missingInserts, long untransformed) {
    }

    private Verify verify(Map<Long, java.util.Set<String>> writesById, List<Long> insertedIds) throws SQLException {
        long lost = 0;
        long untransformed = 0;
        try (Connection con = open();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT note FROM " + TABLE + " WHERE id = ?")) {
            for (Map.Entry<Long, java.util.Set<String>> e : writesById.entrySet()) {
                ps.setLong(1, e.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        lost++;
                    } else {
                        String note = rs.getString(1);
                        // 유실 = 그 행에 쓴 어떤 값에서도 유도되지 않는 최종 값. 커밋 순서는 장부로 못 가려 집합 기준
                        if (e.getValue().contains(note)) {
                            untransformed++;
                        } else if (!note.startsWith("v2:") || !e.getValue().contains(note.substring(3))) {
                            lost++;
                        }
                    }
                }
            }
        }
        long missingInserts = 0;
        try (Connection con = open();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT COUNT(*) FROM " + TABLE + " WHERE id = ?")) {
            for (Long id : insertedIds) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) == 0) {
                        missingInserts++;
                    }
                }
            }
        }
        return new Verify(lost, missingInserts, untransformed);
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

    private static long maxGap(List<Long> commitInstants) {
        List<Long> sorted = new ArrayList<>(commitInstants);
        Collections.sort(sorted);
        long max = 0;
        for (int i = 1; i < sorted.size(); i++) {
            max = Math.max(max, sorted.get(i) - sorted.get(i - 1));
        }
        return max / 1_000_000;
    }

    private long tableBytes(String table) throws SQLException {
        try (Connection con = open();
             PreparedStatement ps = con.prepareStatement("SELECT pg_total_relation_size(?)")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0; // 테이블이 없으면 추가 공간 0
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private void write(String startedAt) throws Exception {
        StringBuilder csv = new StringBuilder("mode,total_ms,cutover_ms,mixed_exposure_ms,lost,"
                + "missing_inserts,untransformed,writer_p50_ms,writer_p95_ms,writer_max_ms,"
                + "writer_max_gap_ms,writer_commits,extra_space_bytes,slot_backlog_max_bytes,cutover_leak_rows\n");
        StringBuilder md = new StringBuilder();
        md.append("# E7b: 온라인 변경 — 배치 커밋 대 트리거 교체 (PostgreSQL)\n\n");
        md.append("이 문서와 `online-swap-postgres.csv`는 `OnlineSwapPostgresExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.\n\n");
        md.append("- 실행 일시: ").append(startedAt).append('\n');
        md.append("- 대상: 전용 PostgreSQL 16(16434, wal_level=logical, tmpfs)\n");
        md.append("- 변경: `swap_scale` ").append(String.format(Locale.ROOT, "%,d", ROWS))
                .append("행의 note에 'v2:' 접두. BATCH는 제자리 1,000행 배치(쉼 100ms), "
                        + "SWAP_TRIGGER는 pt-osc 순서(트리거 설치 → 양보 복사 → 한 트랜잭션 RENAME)\n");
        md.append("- 동시 writer ").append(WRITERS).append("개: 임의 행 UPDATE 90% + 새 행 INSERT 10%\n");
        md.append("- 트리거는 동기 캡처라 drain이 없고 그 비용은 writer 소요에 직접 실린다 — "
                + "BATCH 대비 writer p95 차이가 트리거 오버헤드다\n");
        md.append("- slot backlog: 소비자 없는 logical 슬롯(test_decoding)의 WAL 적체 최대 — "
                + "캡처 스트림을 켜 두는 것 자체의 비용 관측\n\n");
        md.append("| 방식 | 총 소요 | 컷오버 | 혼합 노출 | 유실 | INSERT 누락 | 변환 누락 "
                + "| writer p50 | p95 | max | 최장 공백 | 커밋 수 | 추가 공간 | 슬롯 적체 최대 | 컷오버 유출 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Run r : runs) {
            csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d%n",
                    r.mode(), r.totalMillis(), r.cutoverMillis(), r.mixedExposureMillis(), r.lostRows(),
                    r.missingInserts(), r.untransformedRows(), r.writerP50Ms(), r.writerP95Ms(),
                    r.writerMaxMs(), r.writerMaxGapMs(), r.writerCommits(), r.extraSpaceBytes(),
                    r.slotBacklogMaxBytes(), r.cutoverLeakRows()));
            md.append(String.format(Locale.ROOT,
                    "| %s | %.1f초 | %dms | %.1f초 | %d | %d | %d | %.2fms | %.2fms | %.0fms "
                    + "| %dms | %d | %.0fMB | %.1fMB | %d |%n",
                    r.mode(), r.totalMillis() / 1000.0, r.cutoverMillis(), r.mixedExposureMillis() / 1000.0,
                    r.lostRows(), r.missingInserts(), r.untransformedRows(), r.writerP50Ms(), r.writerP95Ms(),
                    r.writerMaxMs(), r.writerMaxGapMs(), r.writerCommits(),
                    r.extraSpaceBytes() / 1048576.0, r.slotBacklogMaxBytes() / 1048576.0, r.cutoverLeakRows()));
        }
        Files.createDirectories(DOC.getParent());
        Files.writeString(CSV, csv, StandardCharsets.UTF_8);
        Files.writeString(DOC, md, StandardCharsets.UTF_8);
    }
}
