import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * SQL Server 부하 전후 측정기 — SQL Server에는 pgbench가 없어, 132절의 PostgreSQL 측정과 같은 모양(클라이언트 N개가 T초 동안 무작위 가맹점의
 * 최근 결제 20건을 조회)을 JDBC로 재현한다. 같은 명령을 네이티브 x64(GitHub Actions 러너)와 Apple Silicon의 Rosetta VM에서 돌려 비교한다
 * (VERIFICATION 134절).
 *
 * <p>클라이언트 쪽 지연(보내고 받을 때까지)과 서버 쪽 평균 실행 시간(sys.dm_exec_query_stats)을 같이 찍는다 — 둘의 차이가 경로 비용이다.
 * 비밀번호는 argv가 아니라 환경변수 DB_PASSWORD로 받는다(AGENTS.md 비밀값 규칙).
 *
 * <pre>
 * DB_PASSWORD='...' java -cp mssql-jdbc.jar scripts/MssqlLoad.java "jdbc:sqlserver://127.0.0.1:11433;databaseName=sample;encrypt=false" sa env
 *   env | prepare | index | drop-index | plan | bench &lt;clients&gt; &lt;seconds&gt; [query|select1]
 * </pre>
 */
public class MssqlLoad {

    static final String QUERY =
            "SELECT TOP (20) id, amount, created_at FROM dbo.payment_events WHERE merchant_id = ? ORDER BY created_at DESC";
    static final int WARMUP_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("사용법: DB_PASSWORD=... java -cp <jdbc.jar> MssqlLoad.java <jdbc-url> <user> "
                    + "env|prepare|index|drop-index|plan|bench <clients> <seconds> [query|select1]");
            System.exit(2);
        }
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isEmpty()) {
            System.err.println("DB_PASSWORD 환경변수가 필요합니다(비밀번호는 인자로 받지 않습니다)");
            System.exit(2);
        }
        Target t = new Target(args[0], args[1], password);
        switch (args[2]) {
            case "env" -> env(t);
            case "prepare" -> prepare(t);
            case "index" -> exec(t, "IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_payment_events_merchant') "
                    + "CREATE INDEX idx_payment_events_merchant ON dbo.payment_events (merchant_id)", "index");
            case "drop-index" -> exec(t, "IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_payment_events_merchant') "
                    + "DROP INDEX idx_payment_events_merchant ON dbo.payment_events", "drop-index");
            case "plan" -> plan(t);
            case "bench" -> bench(t, Integer.parseInt(args[3]), Integer.parseInt(args[4]), args.length > 5 ? args[5] : "query");
            default -> {
                System.err.println("알 수 없는 모드: " + args[2]);
                System.exit(2);
            }
        }
    }

    record Target(String url, String user, String password) {
        Connection open() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }
    }

    static void env(Target t) throws SQLException {
        try (Connection c = t.open(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT @@VERSION, CONVERT(int, SERVERPROPERTY('EngineEdition'))")) {
                rs.next();
                System.out.println("[env] server=" + rs.getString(1).lines().findFirst().orElse("") + " edition=" + rs.getInt(2));
            }
            try (ResultSet rs = st.executeQuery("SELECT host_platform, host_distribution, host_release FROM sys.dm_os_host_info")) {
                rs.next();
                System.out.println("[env] host=" + rs.getString(1) + " / " + rs.getString(2) + " / " + rs.getString(3));
            }
            try (ResultSet rs = st.executeQuery("SELECT cpu_count, physical_memory_kb / 1024 FROM sys.dm_os_sys_info")) {
                rs.next();
                System.out.println("[env] sql_cpu_count=" + rs.getInt(1) + " memory_mb=" + rs.getLong(2));
            }
        }
        System.out.println("[env] client_os_arch=" + System.getProperty("os.arch") + " java=" + System.getProperty("java.version"));
    }

    static void prepare(Target t) throws SQLException {
        long t0 = System.nanoTime();
        try (Connection c = t.open(); Statement st = c.createStatement()) {
            st.execute("""
                    IF OBJECT_ID('dbo.payment_events') IS NULL
                      CREATE TABLE dbo.payment_events (id BIGINT IDENTITY(1,1) PRIMARY KEY, merchant_id INT NOT NULL,
                                                       amount INT NOT NULL, created_at DATETIME2(3) NOT NULL)
                    """);
            // 132절 PostgreSQL 표와 같은 분포: 100만 행, 가맹점 5만 곳(가맹점당 약 20행). 이미 채워져 있으면 그대로 쓴다.
            st.execute("""
                    IF NOT EXISTS (SELECT 1 FROM dbo.payment_events)
                    BEGIN
                      WITH n AS (SELECT TOP (1000000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS rn
                                 FROM sys.all_columns a CROSS JOIN sys.all_columns b)
                      INSERT INTO dbo.payment_events (merchant_id, amount, created_at)
                      SELECT 1 + ABS(CHECKSUM(NEWID())) % 50000, ABS(CHECKSUM(NEWID())) % 100000,
                             DATEADD(SECOND, -CAST(rn AS INT), CAST('2026-09-01' AS DATETIME2(3)))
                      FROM n
                    END
                    """);
            st.execute("UPDATE STATISTICS dbo.payment_events WITH FULLSCAN");
            try (ResultSet rs = st.executeQuery("SELECT COUNT_BIG(*), COUNT(DISTINCT merchant_id) FROM dbo.payment_events")) {
                rs.next();
                System.out.printf("[prepare] rows=%d merchants=%d elapsed_s=%.1f%n", rs.getLong(1), rs.getInt(2),
                        (System.nanoTime() - t0) / 1e9);
            }
        }
    }

    static void exec(Target t, String sql, String label) throws SQLException {
        long t0 = System.nanoTime();
        try (Connection c = t.open(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
        System.out.printf("[%s] elapsed_s=%.2f%n", label, (System.nanoTime() - t0) / 1e9);
    }

    /** 추정 계획 — SHOWPLAN_TEXT는 첫 결과 집합이 문장, 다음이 계획이다(131절 실측). */
    static void plan(Target t) throws SQLException {
        try (Connection c = t.open(); Statement st = c.createStatement()) {
            st.execute("SET SHOWPLAN_TEXT ON");
            boolean has = st.execute(QUERY.replace("?", "12345"));
            int set = 0;
            while (true) {
                if (has) {
                    try (ResultSet rs = st.getResultSet()) {
                        while (rs.next()) {
                            if (set > 0) {
                                System.out.println("[plan] " + rs.getString(1).strip());
                            }
                        }
                    }
                    set++;
                } else if (st.getUpdateCount() == -1) {
                    break;
                }
                has = st.getMoreResults();
            }
            st.execute("SET SHOWPLAN_TEXT OFF");
        }
    }

    static void bench(Target t, int clients, int seconds, String kind) throws Exception {
        boolean select1 = kind.equals("select1");
        String sql = select1 ? "SELECT 1" : QUERY;
        if (!select1) {
            // 전후 구간의 서버 통계가 섞이지 않게 계획 캐시를 비운다(데모 전용 인스턴스라 가능한 조작)
            exec(t, "DBCC FREEPROCCACHE WITH NO_INFOMSGS", "freeproccache");
        }
        LongAdder count = new LongAdder();
        LongAdder nanos = new LongAdder();
        long start = System.nanoTime() + WARMUP_SECONDS * 1_000_000_000L;
        long end = start + seconds * 1_000_000_000L;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            futures.add(pool.submit(() -> {
                try (Connection c = t.open(); PreparedStatement ps = c.prepareStatement(sql)) {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    while (true) {
                        long t0 = System.nanoTime();
                        if (t0 >= end) {
                            return null;
                        }
                        if (!select1) {
                            ps.setInt(1, 1 + random.nextInt(50_000));
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rs.getLong(1);
                            }
                        }
                        if (t0 >= start) {
                            count.increment();
                            nanos.add(System.nanoTime() - t0);
                        }
                    }
                }
            }));
        }
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();
        long n = count.sum();
        System.out.printf("[bench %s] clients=%d seconds=%d tx=%d latency_ms=%.3f tps=%.1f%n", kind, clients, seconds, n,
                n == 0 ? 0 : nanos.sum() / 1e6 / n, n / (double) seconds);
        if (!select1) {
            try (Connection c = t.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                    SELECT SUM(qs.execution_count), SUM(qs.total_elapsed_time) * 1.0 / SUM(qs.execution_count),
                           SUM(qs.total_logical_reads) * 1.0 / SUM(qs.execution_count)
                    FROM sys.dm_exec_query_stats qs CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                    WHERE st.text LIKE '%dbo.payment_events WHERE merchant_id%' AND st.text NOT LIKE '%dm_exec_query_stats%'
                    """)) {
                rs.next();
                System.out.printf("[bench %s server] executions=%d avg_elapsed_us=%.1f avg_logical_reads=%.1f%n", kind,
                        rs.getLong(1), rs.getDouble(2), rs.getDouble(3));
            }
        }
    }
}
