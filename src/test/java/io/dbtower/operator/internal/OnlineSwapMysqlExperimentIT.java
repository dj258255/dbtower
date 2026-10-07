package io.dbtower.operator.internal;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.Serializable;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E7 — 온라인 변경: 배치 커밋 대 ghost 교체(MySQL). #168
 *
 * <p>질문. 현행 배치 커밋이 포기하는 것(혼합 상태 노출, 실행 중 쓰기와의 경합)을
 * ghost 사본 + binlog 재생 + 원자적 RENAME(gh-ost 방식)이 어떤 비용으로 되찾는가.
 *
 * <p>세 arm을 같은 동시 쓰기 부하에서 돌린다.
 * <ul>
 *   <li>BATCH — 현행 방식. 기본 키 구간 1,000행씩 제자리 UPDATE, 배치 사이 100ms</li>
 *   <li>SWAP_NOLOG — ghost에 변환 복사만 하고 교체. 복사 중 쓰기를 따라잡지 않는다(누락을 실측하기 위한 대조군)</li>
 *   <li>SWAP_BINLOG — 복사 시작 시점부터 binlog(ROW)를 재생해 ghost에 적용하고, 두 커넥션
 *       잠금 춤(LOCK → RENAME 대기 → drain → UNLOCK)으로 원자 교체</li>
 * </ul>
 *
 * <p>측정. ① 동시 writer 커밋 대기 p50/p95/max ② 혼합 상태 노출 시간(고정 표본 200행을 50ms마다 읽어
 * 신·구 형식이 공존한 구간) ③ 유실(writer의 마지막 값이 흔적 없이 사라진 행)과 신규 행 누락
 * ④ 변환 누락(writer 값은 남았지만 v2 변환이 안 덮인 행 — 교체 방식의 레이스 비용)
 * ⑤ 컷오버 구간의 writer 최장 커밋 공백 ⑥ 추가 공간.
 *
 * <p>유실 판정 기준(사전): 최종 행 값이 {마지막 writer 값, 'v2:'+마지막 writer 값} 어디에도 없으면 유실.
 * BATCH는 제자리 변환이라 두 형태가 모두 정상이고, SWAP은 복사·재생 순서에 따라 두 형태가 나온다.
 *
 * <p>실행: {@code DBTOWER_EXPERIMENT=1 ./gradlew test --tests '*OnlineSwapMysqlExperimentIT'}
 * 대상은 docker compose의 MySQL 13306. binlog는 MySQL 8.4 기본값(log_bin=ON, ROW)을 그대로 쓰며
 * 꺼져 있으면 실험을 실패시킨다(조용히 건너뛰지 않는다).
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_EXPERIMENT", matches = "1")
class OnlineSwapMysqlExperimentIT {

    private static final Path DOC = Path.of("docs", "experiments", "online-swap-mysql.md");
    private static final Path CSV = Path.of("docs", "experiments", "online-swap-mysql.csv");

    private static final String URL = "jdbc:mysql://127.0.0.1:13306/sample?rewriteBatchedStatements=true";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 13306;
    private static final String USER = "root";
    private static final String PASSWORD = "dbtower1234";
    private static final String SCHEMA = "sample";
    private static final String TABLE = "swap_scale";

    /** 스모크는 DBTOWER_E7_ROWS로 줄인다. 본 측정 기본값은 1,000,000 */
    private static final int ROWS = Integer.parseInt(System.getenv().getOrDefault("DBTOWER_E7_ROWS", "1000000"));
    private static final int BATCH_ROWS = 1_000;
    private static final long PAUSE_MILLIS = 100;
    private static final int WRITERS = 2;
    private static final int SAMPLE_IDS = 200;
    private static final long SAMPLE_INTERVAL_MS = 50;
    private static final int TIMEOUT_SECONDS = 180;

    /** 한 arm의 측정 결과 */
    private record Run(String mode, long totalMillis, long cutoverMillis, long mixedExposureMillis,
                       long lostRows, long missingInserts, long untransformedRows,
                       double writerP50Ms, double writerP95Ms, double writerMaxMs,
                       long writerMaxGapMs, long writerCommits, long extraSpaceBytes) {
    }

    private final List<Run> runs = new ArrayList<>();
    private volatile BinlogReplayer lastReplayer;

    @Test
    void 배치와_ghost_교체를_같은_쓰기_부하에서_잰다() throws Exception {
        String startedAt = OffsetDateTime.now().toString();
        requireRowBinlog();
        for (String mode : List.of("BATCH", "SWAP_NOLOG", "SWAP_BINLOG")) {
            seed();
            runs.add(measure(mode));
            System.out.println("[E7] " + runs.getLast());
        }
        write(startedAt);
        for (Run r : runs) {
            if ("SWAP_NOLOG".equals(r.mode())) {
                continue; // 재생 없는 교체는 유실을 실측하기 위한 대조군이다
            }
            assertEquals(0, r.lostRows(), r.mode() + ": writer 값이 흔적 없이 사라지면 안 된다(사전 기준)");
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void requireRowBinlog() throws SQLException {
        try (Connection con = open(); Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'log_bin'")) {
                rs.next();
                assertEquals("ON", rs.getString(2), "binlog가 꺼져 있다 — 실험 전제를 만족하지 않는다");
            }
            try (ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'binlog_format'")) {
                rs.next();
                assertEquals("ROW", rs.getString(2), "binlog_format=ROW가 아니다");
            }
        }
    }

    /** MySQL 8.4는 SHOW BINARY LOG STATUS, 그 전은 SHOW MASTER STATUS */
    private String[] binlogPosition() throws SQLException {
        try (Connection con = open(); Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SHOW BINARY LOG STATUS")) {
                rs.next();
                return new String[]{rs.getString(1), rs.getString(2)};
            } catch (SQLException e) {
                try (ResultSet rs = st.executeQuery("SHOW MASTER STATUS")) {
                    rs.next();
                    return new String[]{rs.getString(1), rs.getString(2)};
                }
            }
        }
    }

    private void seed() throws SQLException {
        try (Connection con = open(); Statement st = con.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("DROP TABLE IF EXISTS " + TABLE + "_ghost");
            st.execute("DROP TABLE IF EXISTS " + TABLE + "_old");
            st.execute("CREATE TABLE " + TABLE + " (id BIGINT PRIMARY KEY, note VARCHAR(80) NOT NULL)");
        }
        try (Connection con = open()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO " + TABLE + " VALUES (?, ?)")) {
                for (int id = 1; id <= ROWS; id++) {
                    ps.setLong(1, id);
                    ps.setString(2, "n" + id);
                    ps.addBatch();
                    if (id % 20_000 == 0) {
                        ps.executeBatch();
                        con.commit();
                    }
                }
                ps.executeBatch();
                con.commit();
            }
        }
    }

    // ── 측정 본체 ────────────────────────────────────────────────────────────

    private Run measure(String mode) throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        Map<Long, java.util.Set<String>> writesById = new ConcurrentHashMap<>();
        List<Long> insertedIds = Collections.synchronizedList(new ArrayList<>());
        List<Double> latencies = Collections.synchronizedList(new ArrayList<>());
        List<Long> commitInstants = Collections.synchronizedList(new ArrayList<>());
        AtomicLong insertSeq = new AtomicLong(ROWS + 1);

        List<Thread> writers = new ArrayList<>();
        CountDownLatch writersReady = new CountDownLatch(WRITERS);
        for (int w = 0; w < WRITERS; w++) {
            Thread t = new Thread(() -> writerLoop(stop, writersReady, writesById, insertedIds,
                    latencies, commitInstants, insertSeq), "e7-writer-" + w);
            t.start();
            writers.add(t);
        }
        writersReady.await(30, TimeUnit.SECONDS);

        MixedSampler sampler = new MixedSampler();
        Thread samplerThread = new Thread(sampler, "e7-sampler");
        samplerThread.start();

        long started = System.nanoTime();
        long cutoverMillis = switch (mode) {
            case "BATCH" -> {
                runBatch();
                yield 0L;
            }
            case "SWAP_NOLOG" -> runSwap(false);
            case "SWAP_BINLOG" -> runSwap(true);
            default -> throw new IllegalArgumentException(mode);
        };
        long totalMillis = (System.nanoTime() - started) / 1_000_000;

        stop.set(true);
        for (Thread t : writers) {
            t.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
        sampler.stop.set(true);
        samplerThread.join(TimeUnit.SECONDS.toMillis(30));

        long extraSpace = tableBytes(TABLE + "_old");
        Verify v = verify(writesById, insertedIds);
        if ("SWAP_BINLOG".equals(mode) && v.missingInserts() > 0 && lastReplayer != null) {
            for (Long id : insertedIds) {
                try (Connection c = open(); PreparedStatement q = c.prepareStatement(
                        "SELECT COUNT(*) FROM " + TABLE + " WHERE id = ?")) {
                    q.setLong(1, id);
                    try (ResultSet rs = q.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) == 0) {
                            System.out.println("[E7:miss] id=" + id
                                    + " insertEventSeen=" + lastReplayer.seenInsertIds.contains(id)
                                    + " applied=" + lastReplayer.appliedIds.contains(id));
                        }
                    }
                }
            }
        }
        double[] pct = percentiles(latencies);
        long maxGap = maxGap(commitInstants);
        try (Connection con = open(); Statement st = con.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE + "_old");
        }
        return new Run(mode, totalMillis, cutoverMillis, sampler.mixedExposureMillis(),
                v.lost(), v.missingInserts(), v.untransformed(),
                pct[0], pct[1], pct[2], maxGap, latencies.size(), extraSpace);
    }

    private void writerLoop(AtomicBoolean stop, CountDownLatch ready, Map<Long, java.util.Set<String>> writesById,
                            List<Long> insertedIds, List<Double> latencies, List<Long> commitInstants,
                            AtomicLong insertSeq) {
        Random random = new Random();
        try (Connection con = open()) {
            con.setAutoCommit(false);
            PreparedStatement update = con.prepareStatement(
                    "UPDATE " + TABLE + " SET note = ? WHERE id = ?");
            PreparedStatement insert = con.prepareStatement(
                    "INSERT INTO " + TABLE + " VALUES (?, ?)");
            ready.countDown();
            long seq = 0;
            while (!stop.get()) {
                long t0 = System.nanoTime();
                try {
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
                    // 컷오버 RENAME 순간의 일시 오류(테이블 없음 등)는 재시도 대상으로 두고 기록하지 않는다
                    try {
                        con.rollback();
                    } catch (SQLException ignored) {
                        // 커넥션이 죽은 경우는 루프 종료 조건에서 드러난다
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("writer 실패", e);
        }
    }

    // ── arm 구현 ────────────────────────────────────────────────────────────

    private void runBatch() throws SQLException, InterruptedException {
        try (Connection con = open()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE " + TABLE + " SET note = CONCAT('v2:', note) " +
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
    }

    /** ghost 복사(+선택적 binlog 재생) 후 두 커넥션 잠금 춤으로 교체. 반환값은 컷오버 구간 ms */
    private long runSwap(boolean replayBinlog) throws Exception {
        try (Connection con = open(); Statement st = con.createStatement()) {
            st.execute("CREATE TABLE " + TABLE + "_ghost LIKE " + TABLE);
        }
        BinlogReplayer replayer = null;
        if (replayBinlog) {
            // gh-ost처럼 복사 전에 좌표를 따서 연결 지연 공백의 쓰기도 재생 범위에 넣는다
            String[] pos = binlogPosition();
            replayer = new BinlogReplayer();
            lastReplayer = replayer;
            replayer.start(pos[0], Long.parseLong(pos[1]));
        }
        // gh-ost 불변식: 좌표 캡처 뒤에 복사 범위(MAX id)를 정해야 캡처 전 행은 복사가, 캡처 후 행은 재생이 책임진다
        long maxId;
        try (Connection con = open(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(id) FROM " + TABLE)) {
            rs.next();
            maxId = rs.getLong(1);
        }
        try (Connection con = open()) {
            con.setAutoCommit(false);
            // 복사가 만드는 binlog를 재생 클라이언트가 소화하면 drain(컷오버)이 길어진다(1차 측정 3.9초).
            // 복제 없는 단일 인스턴스 전제로 복사·적용은 로그에서 뺀다. 복제가 있으면 이 선택지는 없다
            try (Statement st = con.createStatement()) {
                st.execute("SET sql_log_bin = 0");
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT IGNORE INTO " + TABLE + "_ghost " +
                    "SELECT id, CONCAT('v2:', note) FROM " + TABLE + " WHERE id BETWEEN ? AND ?")) {
                for (long from = 1; from <= maxId; from += BATCH_ROWS) {
                    ps.setLong(1, from);
                    ps.setLong(2, Math.min(from + BATCH_ROWS - 1, maxId));
                    ps.executeUpdate();
                    con.commit();
                }
            }
        }
        long cutStart = System.nanoTime();
        // gh-ost 순서: 잠금을 먼저 잡아 쓰기를 멈춘 뒤에야 drain이 끝날 수 있다(잠금 전에는 이벤트가 계속 온다)
        try (Connection lockCon = open(); Statement lockSt = lockCon.createStatement()) {
            lockSt.execute("LOCK TABLES " + TABLE + " WRITE");
            if (replayer != null) {
                // 좌표 기반 drain: 잠금 뒤의 마스터 좌표를 클라이언트가 지나야 따라잡은 것이다(시간 기준은 부하에 흔들린다)
                String[] target = binlogPosition();
                replayer.awaitPosition(target[0], Long.parseLong(target[1]));
            }
            Thread renamer = new Thread(() -> {
                try (Connection renameCon = open(); Statement renameSt = renameCon.createStatement()) {
                    renameSt.execute("RENAME TABLE " + TABLE + " TO " + TABLE + "_old, "
                            + TABLE + "_ghost TO " + TABLE);
                } catch (SQLException e) {
                    throw new IllegalStateException("RENAME 실패", e);
                }
            }, "e7-renamer");
            renamer.start();
            Thread.sleep(100); // RENAME이 메타데이터 락 대기에 들어갈 시간
            lockSt.execute("UNLOCK TABLES");
            renamer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
        long cutoverMillis = (System.nanoTime() - cutStart) / 1_000_000;
        if (replayer != null) {
            replayer.close();
        }
        return cutoverMillis;
    }

    /** binlog(ROW)를 복사 시작 시점부터 따라가 ghost에 적용한다 — gh-ost의 재생 축 */
    private final class BinlogReplayer implements AutoCloseable {
        private final BinaryLogClient client = new BinaryLogClient(HOST, PORT, USER, PASSWORD);
        private volatile long tableId = -1;
        private final AtomicLong applied = new AtomicLong();
        private final java.util.Set<Long> appliedIds = ConcurrentHashMap.newKeySet();
        private final java.util.Set<Long> seenInsertIds = ConcurrentHashMap.newKeySet();
        private final AtomicLong seen = new AtomicLong();
        private volatile Exception firstError;
        private volatile long lastEventNanos = System.nanoTime();
        /** 리스너(적용)까지 끝난 좌표. 커넥터의 좌표는 리스너 실행 전에 갱신돼 drain 기준으로 못 쓴다 */
        private volatile String appliedFile = "";
        private volatile long appliedPos;
        private final Connection applyCon;
        private final PreparedStatement replace;
        private final PreparedStatement delete;
        private Thread thread;

        private BinlogReplayer() throws SQLException {
            applyCon = open();
            applyCon.setAutoCommit(true);
            try (Statement st = applyCon.createStatement()) {
                st.execute("SET sql_log_bin = 0"); // 적용이 다시 이벤트를 만들지 않게
            }
            replace = applyCon.prepareStatement(
                    "REPLACE INTO " + TABLE + "_ghost VALUES (?, ?)");
            delete = applyCon.prepareStatement(
                    "DELETE FROM " + TABLE + "_ghost WHERE id = ?");
        }

        private void start(String binlogFile, long binlogPos) throws Exception {
            client.setServerId(90168);
            client.setBinlogFilename(binlogFile);
            client.setBinlogPosition(binlogPos);
            client.registerEventListener(this::onEvent);
            CountDownLatch connected = new CountDownLatch(1);
            client.registerLifecycleListener(new BinaryLogClient.AbstractLifecycleListener() {
                @Override
                public void onConnect(BinaryLogClient c) {
                    connected.countDown();
                }
            });
            thread = new Thread(() -> {
                try {
                    client.connect();
                } catch (Exception e) {
                    throw new IllegalStateException("binlog 연결 실패", e);
                }
            }, "e7-binlog");
            thread.start();
            assertTrue(connected.await(20, TimeUnit.SECONDS), "binlog 클라이언트가 연결되지 않았다");
        }

        private void onEvent(Event event) {
            seen.incrementAndGet();
            try {
                if (event.getData() instanceof RotateEventData rotate) {
                    appliedFile = rotate.getBinlogFilename();
                }
                if (event.getData() instanceof TableMapEventData map) {
                    if (SCHEMA.equals(map.getDatabase()) && TABLE.equals(map.getTable())) {
                        tableId = map.getTableId();
                    }
                    return;
                }
                EventType type = event.getHeader().getEventType();
                if (EventType.isWrite(type) && event.getData() instanceof WriteRowsEventData d
                        && d.getTableId() == tableId) {
                    for (Serializable[] row : d.getRows()) {
                        seenInsertIds.add(((Number) row[0]).longValue());
                        apply(row);
                    }
                } else if (EventType.isUpdate(type) && event.getData() instanceof UpdateRowsEventData d
                        && d.getTableId() == tableId) {
                    for (Map.Entry<Serializable[], Serializable[]> row : d.getRows()) {
                        apply(row.getValue());
                    }
                } else if (EventType.isDelete(type) && event.getData() instanceof DeleteRowsEventData d
                        && d.getTableId() == tableId) {
                    for (Serializable[] row : d.getRows()) {
                        delete.setLong(1, ((Number) row[0]).longValue());
                        delete.executeUpdate();
                    }
                }
                lastEventNanos = System.nanoTime();
                if (event.getHeader() instanceof EventHeaderV4 h4 && h4.getNextPosition() > 0) {
                    appliedPos = h4.getNextPosition();
                }
            } catch (Exception e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }

        private void apply(Serializable[] row) throws SQLException {
            replace.setLong(1, ((Number) row[0]).longValue());
            // 커넥터 버전에 따라 VARCHAR가 byte[] 또는 String으로 온다
            String note = row[1] instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8) : row[1].toString();
            replace.setString(2, note);
            replace.executeUpdate();
            applied.incrementAndGet();
            appliedIds.add(((Number) row[0]).longValue());
        }

        /** 클라이언트가 목표 좌표(파일, 위치)를 지날 때까지 기다린다 */
        private void awaitPosition(String targetFile, long targetPos) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (true) {
                String file = appliedFile.isEmpty() ? client.getBinlogFilename() : appliedFile;
                long pos = appliedPos;
                if (file != null && (file.compareTo(targetFile) > 0
                        || (file.equals(targetFile) && pos >= targetPos))) {
                    return;
                }
                assertTrue(System.nanoTime() < deadline, "binlog drain이 끝나지 않는다: client="
                        + file + ":" + pos + " target=" + targetFile + ":" + targetPos);
                Thread.sleep(20);
            }
        }

        @Override
        public void close() throws Exception {
            System.out.println("[E7:binlog] seen=" + seen + " applied=" + applied + " firstError=" + firstError);
            client.disconnect();
            if (thread != null) {
                thread.join(TimeUnit.SECONDS.toMillis(30));
            }
            replace.close();
            delete.close();
            applyCon.close();
        }
    }

    /** 고정 표본 행을 주기 조회해 신·구 형식이 공존한 시간을 잰다 */
    private final class MixedSampler implements Runnable {
        private final AtomicBoolean stop = new AtomicBoolean(false);
        private volatile long firstMixedNanos = -1;
        private volatile long lastMixedNanos = -1;

        @Override
        public void run() {
            long step = Math.max(1, ROWS / SAMPLE_IDS);
            StringBuilder ids = new StringBuilder();
            for (long id = 1; id <= ROWS; id += step) {
                if (!ids.isEmpty()) {
                    ids.append(',');
                }
                ids.append(id);
            }
            String sql = "SELECT SUM(note LIKE 'v2:%'), COUNT(*) FROM " + TABLE
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
                        // 컷오버 RENAME 순간의 일시 오류는 다음 주기에 다시 읽는다
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

    // ── 검증 ────────────────────────────────────────────────────────────────

    private record Verify(long lost, long missingInserts, long untransformed) {
    }

    /** 최종 테이블을 writer 기록과 대조한다. 유실 / 신규 행 누락 / 변환 누락을 센다 */
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
                        continue;
                    }
                    String note = rs.getString(1);
                    // 유실 = 그 행에 쓴 어떤 값에서도 유도되지 않는 최종 값(원본 복제 포함).
                    // 두 writer의 커밋 순서까지는 이 장부로 가릴 수 없어 "쓴 값 집합" 기준으로 판정한다
                    if (e.getValue().contains(note)) {
                        untransformed++;
                    } else if (!note.startsWith("v2:") || !e.getValue().contains(note.substring(3))) {
                        lost++;
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

    // ── 집계·기록 ───────────────────────────────────────────────────────────

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
             PreparedStatement ps = con.prepareStatement(
                     "SELECT data_length + index_length FROM information_schema.tables "
                     + "WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, SCHEMA);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private void write(String startedAt) throws Exception {
        StringBuilder csv = new StringBuilder("mode,total_ms,cutover_ms,mixed_exposure_ms,lost,"
                + "missing_inserts,untransformed,writer_p50_ms,writer_p95_ms,writer_max_ms,"
                + "writer_max_gap_ms,writer_commits,extra_space_bytes\n");
        StringBuilder md = new StringBuilder();
        md.append("# E7: 온라인 변경 — 배치 커밋 대 ghost 교체 (MySQL)\n\n");
        md.append("이 문서와 `online-swap-mysql.csv`는 `OnlineSwapMysqlExperimentIT`가 생성했다. 숫자를 손으로 고치지 않는다.\n\n");
        md.append("- 실행 일시: ").append(startedAt).append('\n');
        md.append("- 대상: docker compose MySQL 8.4(13306), binlog ROW 기본값\n");
        md.append("- 변경: `swap_scale` ").append(String.format(Locale.ROOT, "%,d", ROWS))
                .append("행의 note에 'v2:' 접두를 더한다. BATCH는 제자리 1,000행 배치(쉼 100ms), SWAP은 ghost 복사 후 원자 RENAME\n");
        md.append("- 동시 writer ").append(WRITERS).append("개: 임의 행 UPDATE 90% + 새 행 INSERT 10%, 커밋마다 소요 기록\n");
        md.append("- 혼합 노출: 고정 표본 ").append(SAMPLE_IDS).append("행을 ").append(SAMPLE_INTERVAL_MS)
                .append("ms마다 읽어 신·구 형식이 공존한 구간\n");
        md.append("- SWAP의 복사·적용 커넥션은 sql_log_bin=0(복제 없는 단일 인스턴스 전제). 복제가 있으면 끌 수 없고 drain 비용이 컷오버에 더해진다(끈 적 없는 1차 측정에서 3.9초)\n");
        md.append("- 유실 판정(사전): 최종 값이 {마지막 writer 값, 'v2:'+그 값} 어디에도 없으면 유실. "
                + "변환 누락은 writer 값만 남고 v2가 안 덮인 행(교체 방식의 레이스 비용)\n\n");
        md.append("| 방식 | 총 소요 | 컷오버 | 혼합 노출 | 유실 | INSERT 누락 | 변환 누락 "
                + "| writer p50 | p95 | max | 최장 공백 | 커밋 수 | 추가 공간 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Run r : runs) {
            csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%d,%d,%d%n",
                    r.mode(), r.totalMillis(), r.cutoverMillis(), r.mixedExposureMillis(), r.lostRows(),
                    r.missingInserts(), r.untransformedRows(), r.writerP50Ms(), r.writerP95Ms(),
                    r.writerMaxMs(), r.writerMaxGapMs(), r.writerCommits(), r.extraSpaceBytes()));
            md.append(String.format(Locale.ROOT,
                    "| %s | %.1f초 | %dms | %.1f초 | %d | %d | %d | %.2fms | %.2fms | %.0fms | %dms | %d | %.0fMB |%n",
                    r.mode(), r.totalMillis() / 1000.0, r.cutoverMillis(), r.mixedExposureMillis() / 1000.0,
                    r.lostRows(), r.missingInserts(), r.untransformedRows(), r.writerP50Ms(), r.writerP95Ms(),
                    r.writerMaxMs(), r.writerMaxGapMs(), r.writerCommits(), r.extraSpaceBytes() / 1048576.0));
        }
        Files.createDirectories(DOC.getParent());
        Files.writeString(CSV, csv, StandardCharsets.UTF_8);
        Files.writeString(DOC, md, StandardCharsets.UTF_8);
    }
}
