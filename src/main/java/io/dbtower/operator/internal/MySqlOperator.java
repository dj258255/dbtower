package io.dbtower.operator.internal;

import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.operator.model.StatsHealth;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.model.DbParameter;
import io.dbtower.operator.model.DeadlockEvent;
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
import io.dbtower.operator.model.TableStat;
import io.dbtower.operator.model.WaitEvent;

import io.dbtower.registry.DatabaseInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 어댑터.
 *
 * 통계 소스: performance_schema.events_statements_summary_by_digest
 * 주의 — digest는 max_digest_length(기본 1024B)까지만 정규화되므로, 앞부분이 같은 긴 쿼리들이
 * 하나로 뭉개질 수 있다. docker-compose에서 4096으로 늘려 운영한다.
 */
public class MySqlOperator extends AbstractJdbcOperator {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MySqlOperator.class);

    private final HistogramSnapshotStore histogramStore;

    public MySqlOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools,
                         HistogramSnapshotStore histogramStore) {
        super(instance, pools, backupTools);
        this.histogramStore = histogramStore;
    }

    /**
     * MySQL 백업 = 클라이언트 도구(mysqldump) 실행 모델.
     * --single-transaction: InnoDB에서 락 없이 일관된 스냅샷으로 덤프 (MVCC 활용)
     */
    @Override
    public BackupResult backup(BackupPolicy policy) {
        if (policy.type() == BackupPolicy.BackupType.LOG) {
            throw new UnsupportedOperationException("MySQL 로그 백업은 binlog 아카이빙으로 별도 구성 필요");
        }
        java.nio.file.Path out = java.nio.file.Path.of(backupTools.backupDir(),
                "mysql-%s-%s.sql".formatted(safeFileName(instance.getName()), backupTimestamp()));
        // 비밀번호는 argv가 아니라 MYSQL_PWD 환경변수로 — ps로 노출되지 않게
        return runCliBackup(renderCommand(backupTools.mysqldumpCommand()),
                java.util.Map.of("MYSQL_PWD", instance.getPassword()), out);
    }

    /**
     * MySQL 복원 검증 = 덤프를 격리된 임시 DB에 실제로 복원해 보는 진짜 restore test.
     * 덤프의 CREATE DATABASE/USE 행을 제거해 원본이 아니라 임시 DB로만 적재하고(핵심 안전장치),
     * 성공 시 복원된 테이블 수로 sanity check, 마지막에 임시 DB를 삭제한다. 정리가 실패해도 원본은 무해.
     */
    @Override
    public RestoreVerification verifyRestore(String location) {
        java.nio.file.Path dump = java.nio.file.Path.of(location);
        if (!java.nio.file.Files.isRegularFile(dump)) {
            return RestoreVerification.failed("덤프 파일을 찾을 수 없습니다: " + location);
        }
        String target = RestoreSupport.verifyTargetName();
        RestoreSupport.requireSafeName(target);
        java.util.Map<String, String> env = java.util.Map.of("MYSQL_PWD", instance.getPassword());
        java.util.List<String> base = renderCommand(backupTools.mysqlRestoreCommand());
        boolean created = false;
        try {
            RestoreSupport.ExecResult create = RestoreSupport.exec(
                    RestoreSupport.concat(base, "-e", "CREATE DATABASE `" + target + "`"), env, null);
            if (!create.ok()) {
                return RestoreVerification.failed("임시 DB 생성 실패: " + create.errorTail());
            }
            created = true;
            RestoreSupport.ExecResult load = RestoreSupport.exec(
                    RestoreSupport.concat(base, target), env, RestoreSupport.stripDatabaseSelection(dump));
            if (!load.ok()) {
                return RestoreVerification.failed("덤프 복원 실패: " + load.errorTail());
            }
            RestoreSupport.ExecResult count = RestoreSupport.exec(RestoreSupport.concat(base, "-N", "-B", "-e",
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + target + "'"), env, null);
            return RestoreVerification.verified(
                    "임시 DB로 덤프 복원 성공 (mysql 클라이언트, target=" + target + ")",
                    RestoreSupport.parseCount(count));
        } finally {
            if (created) {
                // 정리 실패해도 원본과 무관 — best effort로 임시 DB만 삭제
                RestoreSupport.exec(RestoreSupport.concat(base, "-e",
                        "DROP DATABASE IF EXISTS `" + target + "`"), env, null);
            }
        }
    }

    @Override
    protected String jdbcUrl() {
        // useTls면 REQUIRED — 암호화를 강제하되 인증서 검증은 JVM truststore 기본을 따른다.
        // 미지정 시 드라이버 기본(PREFERRED: 서버가 지원하면 암호화) 유지 — 기존 등록 하위 호환.
        String ssl = instance.isUseTls() ? "&sslMode=REQUIRED" : "";
        return "jdbc:mysql://%s:%d/%s?connectTimeout=3000&socketTimeout=15000%s"
                .formatted(instance.getHost(), instance.getPort(), instance.getDbName(), ssl);
    }

    @Override
    protected String versionSql() {
        return "SELECT VERSION()";
    }

    @Override
    public List<QueryStat> queryStats(int limit) {
        // TIMER_WAIT 계열은 피코초 단위라 1e9로 나눠 ms로 환산한다
        String sql = """
                SELECT DIGEST, DIGEST_TEXT, COUNT_STAR,
                       SUM_TIMER_WAIT / 1000000000 AS total_ms,
                       SUM_ROWS_EXAMINED
                FROM performance_schema.events_statements_summary_by_digest
                WHERE DIGEST_TEXT IS NOT NULL
                ORDER BY SUM_TIMER_WAIT DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new QueryStat(
                            rs.getString("DIGEST"),
                            rs.getString("DIGEST_TEXT"),
                            rs.getLong("COUNT_STAR"),
                            rs.getDouble("total_ms"),
                            rs.getLong("SUM_ROWS_EXAMINED")),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
    }

    /** 히스토그램 버킷 수 (events_statements_histogram_by_digest는 digest당 BUCKET_NUMBER 0~449 고정). */
    private static final int HISTOGRAM_BUCKETS = 450;

    /**
     * 레이턴시 백분위 (D4a → 2차 아크 B-1) — 두 단계로 정직 등급을 올린다.
     *
     * <p><b>NATIVE(누적) → NATIVE_WINDOWED(최근 구간).</b> MySQL 8.0+는 두 곳에 원자료를 둔다 —
     * events_statements_summary_by_digest의 QUANTILE_95/99(재기동 이후 <b>누적</b> 백분위)와
     * events_statements_histogram_by_digest의 450버킷 히스토그램(역시 누적 카운트)이다. 누적 백분위는
     * 오래 뜬 서버일수록 과거에 눌려 최근 급변을 늦게 반영한다. 그래서 히스토그램을 직전 스냅샷과
     * <b>버킷별로 차분</b>해(→ 두 호출 사이 "최근 구간") 누적 95% 교차 버킷의 상한(BUCKET_TIMER_HIGH)을
     * 구간 p95로 쓴다. 이게 NATIVE_WINDOWED다.
     *
     * <p>정직 규칙: (a) 직전 스냅샷이 없는 <b>첫 호출</b>이거나 (b) 카운터가 감소한 <b>재기동/리셋</b>이면
     * 이번 구간을 만들 수 없으므로 누적 QUANTILE을 NATIVE로 돌려주며 "구간 학습 중" 노트를 단다.
     * (c) 최근 구간에 그 digest 실행이 0회면 표본이 없어 역시 누적으로 폴백한다. <b>TRUNCATE로 히스토그램을
     * 리셋하면 즉시 최근 창을 얻지만 그건 대상 DB 상태 변경(가드레일 위반)</b> — 차분 방식이 바로 그 금지의 대안이다.
     */
    /**
     * 상위 digest 선정 쿼리 (C-2) — 같은 DIGEST가 여러 SCHEMA_NAME 행으로 나뉘어도 digest당 1행이 되도록
     * {@code GROUP BY DIGEST}로 접는다. 접지 않으면 (SCHEMA_NAME,DIGEST) 키라 한 digest가 두 스키마에서
     * 실행됐을 때 tops에 2행이 잡히고, 뒤의 히스토그램 스냅샷 키(instance:mysql-hist:digest)가 충돌해
     * 허위 "학습 중"이 반복됐다. 정렬은 스키마 합산 부하(SUM(SUM_TIMER_WAIT))로, 텍스트는 대표값(MAX),
     * 누적 QUANTILE은 대표값으로 스키마별 최댓값(MAX — 최악 스키마의 백분위)을 쓴다.
     */
    static final String LATENCY_TOP_SQL = """
            SELECT DIGEST,
                   MAX(DIGEST_TEXT) AS DIGEST_TEXT,
                   MAX(QUANTILE_95) / 1000000000 AS p95_ms,
                   MAX(QUANTILE_99) / 1000000000 AS p99_ms
            FROM performance_schema.events_statements_summary_by_digest
            WHERE DIGEST_TEXT IS NOT NULL AND DIGEST IS NOT NULL
            GROUP BY DIGEST
            ORDER BY SUM(SUM_TIMER_WAIT) DESC
            LIMIT ?
            """;

    @Override
    public List<LatencyPercentile> latencyPercentiles(int limit) {
        try {
            List<TopDigest> tops = jdbc().query(LATENCY_TOP_SQL,
                    (rs, i) -> new TopDigest(rs.getString("DIGEST"), rs.getString("DIGEST_TEXT"),
                            round2(rs.getDouble("p95_ms")), round2(rs.getDouble("p99_ms"))),
                    limit);
            if (tops.isEmpty()) {
                return List.of();
            }

            // 상위 digest들의 히스토그램 버킷을 한 번에 — 누적 COUNT_BUCKET + 고정 버킷 상한(ms).
            Map<String, long[]> curCounts = new HashMap<>();
            double[] upperMs = new double[HISTOGRAM_BUCKETS]; // 버킷 상한은 digest 무관 동일 사실
            String inClause = String.join(",", java.util.Collections.nCopies(tops.size(), "?"));
            String histSql = "SELECT DIGEST, BUCKET_NUMBER, BUCKET_TIMER_HIGH, COUNT_BUCKET "
                    + "FROM performance_schema.events_statements_histogram_by_digest "
                    + "WHERE DIGEST IN (" + inClause + ")";
            Object[] histArgs = tops.stream().map(TopDigest::digest).toArray();
            boolean[] histAvailable = {false};
            try {
                jdbc().query(histSql, rs -> {
                    histAvailable[0] = true;
                    String d = rs.getString("DIGEST");
                    int b = rs.getInt("BUCKET_NUMBER");
                    if (b < 0 || b >= HISTOGRAM_BUCKETS) {
                        return;
                    }
                    // 같은 digest가 복수 스키마로 나뉘면 버킷 카운트를 합산한다.
                    curCounts.computeIfAbsent(d, k -> new long[HISTOGRAM_BUCKETS])[b]
                            += rs.getLong("COUNT_BUCKET");
                    // 마지막 버킷의 BUCKET_TIMER_HIGH는 unsigned bigint 최댓값(2^64-1)인 "무한대" 센티넬이라
                    // getLong()이면 signed long 범위 초과로 터진다 — BigDecimal로 받아 안전히 ms 환산.
                    java.math.BigDecimal high = rs.getBigDecimal("BUCKET_TIMER_HIGH");
                    upperMs[b] = high == null ? 0.0 : high.doubleValue() / 1_000_000_000.0;
                }, histArgs);
            } catch (DataAccessException histEx) {
                // 히스토그램 뷰 조회 실패(소비자 꺼짐 / 권한 없음 등) — 누적 NATIVE로 전량 폴백(정직).
                // 왜 폴백했는지 조용히 삼키지 않고 남긴다(관측성): 최소권한 계정이 이 뷰 권한만 빠졌는지 등을 알 수 있게.
                histAvailable[0] = false;
                LOG.warn("MySQL 히스토그램(events_statements_histogram_by_digest) 조회 실패 — 구간 p95 대신 누적 폴백: {}",
                        histEx.getMessage());
            }

            List<LatencyPercentile> out = new ArrayList<>(tops.size());
            for (TopDigest t : tops) {
                long[] cur = histAvailable[0] ? curCounts.get(t.digest()) : null;
                if (cur == null) {
                    out.add(new LatencyPercentile(t.digest(),
                            t.text() + "  [히스토그램 미수집 — 누적값]", t.p95(), t.p99(),
                            LatencyPercentile.NATIVE));
                    continue;
                }
                long[] prev = histogramStore.swap(instance.getId() + ":mysql-hist:" + t.digest(), cur);
                long[] delta = HistogramPercentile.windowDiff(prev, cur);
                if (delta == null) {
                    out.add(new LatencyPercentile(t.digest(),
                            t.text() + "  [구간 학습 중 — 다음 주기부터 최근 윈도우]", t.p95(), t.p99(),
                            LatencyPercentile.NATIVE));
                    continue;
                }
                Double p95 = HistogramPercentile.bucketCeiling(upperMs, delta, 0.95);
                if (p95 == null) {
                    out.add(new LatencyPercentile(t.digest(),
                            t.text() + "  [최근 구간 실행 없음 — 누적값]", t.p95(), t.p99(),
                            LatencyPercentile.NATIVE));
                    continue;
                }
                Double p99 = HistogramPercentile.bucketCeiling(upperMs, delta, 0.99);
                out.add(new LatencyPercentile(t.digest(), t.text(),
                        round2(p95), p99 == null ? null : round2(p99),
                        LatencyPercentile.NATIVE_WINDOWED));
            }
            return out;
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 레이턴시 백분위 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 상위 digest의 누적 QUANTILE(폴백용)과 텍스트를 잠깐 담는 내부 운반체. */
    private record TopDigest(String digest, String text, Double p95, Double p99) {
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 파티션 조회 (D5) — information_schema.PARTITIONS. 파티션이 있는 행만(PARTITION_NAME NOT NULL —
     * 비파티션 테이블은 이 뷰에 파티션명 NULL인 한 행으로 나타나므로 걸러낸다). 읽기 전용.
     * boundary는 PARTITION_DESCRIPTION(RANGE면 "VALUES LESS THAN (...)", LIST면 값 목록, HASH/KEY면 없음).
     */
    @Override
    public List<PartitionInfo> partitions(int limit) {
        String sql = """
                SELECT TABLE_NAME, PARTITION_NAME, PARTITION_METHOD, PARTITION_EXPRESSION,
                       PARTITION_DESCRIPTION, TABLE_ROWS, DATA_LENGTH
                FROM information_schema.PARTITIONS
                WHERE TABLE_SCHEMA = ? AND PARTITION_NAME IS NOT NULL
                ORDER BY TABLE_NAME, PARTITION_ORDINAL_POSITION
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new PartitionInfo(
                            rs.getString("TABLE_NAME"),
                            rs.getString("PARTITION_NAME"),
                            rs.getString("PARTITION_METHOD"),
                            rs.getString("PARTITION_EXPRESSION"),
                            rs.getString("PARTITION_DESCRIPTION"),
                            rs.getObject("TABLE_ROWS", Long.class),
                            rs.getObject("DATA_LENGTH", Long.class)),
                    instance.getDbName(), limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 파티션 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 인덱스 사용 통계 (D6) — performance_schema.table_io_waits_summary_by_index_usage의 COUNT_STAR가
     * 인덱스별 I/O 사용 누적 횟수다(sys.schema_unused_indexes 뷰의 근거와 동일). COUNT_STAR=0이면 통계
     * 리셋(서버 재기동) 이후 미사용 후보. INDEX_NAME IS NULL 행은 인덱스가 아니라 "테이블 풀스캔" 버킷이라
     * 제외한다. 유니크 여부는 information_schema.STATISTICS.NON_UNIQUE로 판정(PRIMARY 등 제약 인덱스 제외용).
     * performance_schema가 꺼져 있으면 조회가 실패하고 상위에서 ERROR로 격리된다. 읽기 전용.
     */
    @Override
    public List<IndexUsage> indexUsage(int limit) {
        String sql = """
                SELECT t.OBJECT_NAME AS table_name, t.INDEX_NAME AS index_name,
                       t.COUNT_STAR AS scan_count,
                       MAX(CASE WHEN s.NON_UNIQUE = 0 THEN 1 ELSE 0 END) AS is_unique
                FROM performance_schema.table_io_waits_summary_by_index_usage t
                LEFT JOIN information_schema.STATISTICS s
                       ON s.TABLE_SCHEMA = t.OBJECT_SCHEMA
                      AND s.TABLE_NAME = t.OBJECT_NAME
                      AND s.INDEX_NAME = t.INDEX_NAME
                WHERE t.OBJECT_SCHEMA = ? AND t.INDEX_NAME IS NOT NULL
                GROUP BY t.OBJECT_NAME, t.INDEX_NAME, t.COUNT_STAR
                ORDER BY t.COUNT_STAR ASC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new IndexUsage(
                            rs.getString("table_name"),
                            rs.getString("index_name"),
                            rs.getObject("scan_count", Long.class),
                            // MySQL은 인덱스별 크기를 간단히 주지 않아 크기는 담지 않는다(미사용 판정에는 불필요)
                            null,
                            rs.getInt("is_unique") == 1,
                            IndexUsage.NATIVE),
                    instance.getDbName(), limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 인덱스 사용 통계 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 슬로우 쿼리 조회 SQL (C-1) — {@code TIME_TO_SEC}는 정수 초라 그것만 *1000하면 1초 미만 쿼리가 전부
     * 0ms로 뭉개진다. {@code MICROSECOND(query_time)/1000}(마이크로초→ms)을 더해 밀리초 이하를 살린다.
     * 정직한 한계: mysql.slow_log는 {@code log_output=TABLE}일 때 query_time을 초 정밀도(TIME)로만
     * 저장하는 경우가 있어(서버·버전에 따라 MICROSECOND가 0으로 나옴) 이 보정으로도 sub-ms까지는
     * 완전하지 않다. 마이크로초를 온전히 보존하려면 log_output=FILE로 전환해야 하는데 그것은 대상 DB
     * 설정 변경이라 범위 밖 — TABLE 안에서의 최선 보정이다.
     */
    static final String SLOW_QUERIES_SQL = """
            SELECT CONVERT(sql_text USING utf8mb4) AS sql_text,
                   TIME_TO_SEC(query_time) * 1000 + MICROSECOND(query_time) / 1000 AS elapsed_ms,
                   TIME_TO_SEC(lock_time) * 1000 + MICROSECOND(lock_time) / 1000 AS lock_ms,
                   rows_examined,
                   rows_sent,
                   CONVERT(user_host USING utf8mb4) AS user_host,
                   start_time
            FROM mysql.slow_log
            ORDER BY start_time DESC
            LIMIT ?
            """;

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        // docker-compose에서 slow_query_log=ON, log_output=TABLE로 켜두어 mysql.slow_log에서 직접 조회한다
        try {
            return jdbc().query(SLOW_QUERIES_SQL,
                    (rs, i) -> new SlowQuery(
                            rs.getString("sql_text"),
                            rs.getDouble("elapsed_ms"),
                            rs.getLong("rows_examined"),
                            rs.getString("start_time"),
                            rs.getString("user_host"),
                            rs.getDouble("lock_ms"),
                            rs.getLong("rows_sent"),
                            null),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 슬로우 쿼리 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TableStat> tableStats(int limit) {
        // TABLE_ROWS는 InnoDB 통계 기반 추정치다 (정확한 COUNT 아님)
        String sql = """
                SELECT TABLE_NAME, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ?
                ORDER BY DATA_LENGTH + INDEX_LENGTH DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new TableStat(
                            rs.getString("TABLE_NAME"),
                            rs.getLong("TABLE_ROWS"),
                            rs.getLong("DATA_LENGTH"),
                            rs.getLong("INDEX_LENGTH")),
                    instance.getDbName(), limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 테이블 통계 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public TableDetail tableDetail(String tableName) {
        String table = TableDetailSupport.requireIdentifier(tableName);
        try {
            // 기본 통계 — TABLE_ROWS·AVG_ROW_LENGTH는 InnoDB 통계 기반 추정치
            Object[] head = jdbc().query("""
                    SELECT ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, AVG_ROW_LENGTH, CREATE_TIME
                    FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                    """,
                    rs -> rs.next() ? new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getString(6)} : null,
                    instance.getDbName(), table);
            if (head == null) {
                return TableDetail.unsupported(table, "테이블을 찾을 수 없습니다: " + table);
            }
            List<TableDetail.IndexDetail> indexes = mysqlIndexes(table);
            // SHOW CREATE TABLE — 식별자는 위에서 검증했고 백틱으로 감싼다(파라미터 바인딩 불가 자리)
            String ddl = jdbc().query("SHOW CREATE TABLE `" + instance.getDbName() + "`.`" + table + "`",
                    rs -> rs.next() ? rs.getString(2) : null);
            return new TableDetail(table, (String) head[0], (Long) head[1], (Long) head[2], (Long) head[3],
                    (Long) head[4], (String) head[5], ddl, TableDetail.DdlSource.NATIVE, indexes,
                    "행수·평균 행 길이는 InnoDB 통계 추정, 카디널리티는 STATISTICS 기준");
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 테이블 상세 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 인덱스 상세 — 복합 인덱스는 SEQ_IN_INDEX 위치별로 CARDINALITY가 누적되므로 마지막(최대)값이 전체 고유값 */
    private List<TableDetail.IndexDetail> mysqlIndexes(String table) {
        record IdxRow(String name, String column, boolean unique, String type, long cardinality) {
        }
        List<IdxRow> rows = jdbc().query("""
                SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE, INDEX_TYPE, IFNULL(CARDINALITY, 0)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY INDEX_NAME, SEQ_IN_INDEX
                """,
                (rs, i) -> new IdxRow(rs.getString(1), rs.getString(2),
                        rs.getInt(3) == 0, rs.getString(4), rs.getLong(5)),
                instance.getDbName(), table);
        Map<String, TableDetail.IndexDetail> byName = new java.util.LinkedHashMap<>();
        for (IdxRow r : rows) {
            byName.merge(r.name(),
                    new TableDetail.IndexDetail(r.name(), List.of(r.column()), r.unique(), r.type(), r.cardinality()),
                    (a, b) -> {
                        List<String> cols = new ArrayList<>(a.columns());
                        cols.addAll(b.columns());
                        long card = Math.max(a.cardinality() == null ? 0 : a.cardinality(),
                                b.cardinality() == null ? 0 : b.cardinality());
                        return new TableDetail.IndexDetail(a.name(), List.copyOf(cols), a.unique(), a.type(), card);
                    });
        }
        return List.copyOf(byName.values());
    }

    @Override
    public String explain(String sql) {
        requireSelect(sql);
        try {
            return jdbc().query("EXPLAIN FORMAT=JSON " + sql,
                    rs -> rs.next() ? rs.getString(1) : "{}");
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL EXPLAIN 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 플랜 변경 감지용 shape (plan flip) — MySQL엔 PG의 GENERIC_PLAN 상당물이 없다. 대신
     * performance_schema가 digest마다 저장해 둔 <b>리터럴 샘플</b>(QUERY_SAMPLE_TEXT)을 EXPLAIN한다
     * (Datadog DBM과 같은 방식). queryId = DIGEST.
     *
     * 함정: 샘플은 performance_schema_max_sql_text_length(기본 1024B)에서 잘릴 수 있는데, 잘린 SQL은
     * EXPLAIN 문법 오류가 나므로 실패 시 스킵(지어내지 않음). 또 샘플은 특정 파라미터 값의 계획이라
     * digest 대표 플랜과 다를 수 있다 — 그래도 "같은 값의 플랜이 바뀌었나"는 유효하게 잡힌다.
     */
    @Override
    public Optional<String> planShapeForDigest(String queryId, String queryText) {
        String sample;
        try {
            sample = jdbc().query("""
                    SELECT QUERY_SAMPLE_TEXT
                    FROM performance_schema.events_statements_summary_by_digest
                    WHERE DIGEST = ? AND QUERY_SAMPLE_TEXT IS NOT NULL
                    ORDER BY QUERY_SAMPLE_SEEN DESC LIMIT 1
                    """, rs -> rs.next() ? rs.getString(1) : null, queryId);
        } catch (DataAccessException e) {
            return Optional.empty();
        }
        if (sample == null || !sample.trim().toLowerCase().startsWith("select")) {
            return Optional.empty(); // 샘플 없음/비 SELECT digest — 스킵
        }
        try {
            return Optional.of(PlanShapes.fromMysqlJson(explain(sample)));
        } catch (RuntimeException e) {
            return Optional.empty(); // 샘플 절단 등으로 EXPLAIN 실패 시 정직하게 스킵
        }
    }

    /**
     * 실제 실행 계획 (D9) — EXPLAIN ANALYZE. 쿼리를 진짜 실행하며 추정(cost=.. rows=EST) 옆에
     * 실측(actual time=.. rows=ACT loops=L)을 함께 준다. actual rows는 loops당 평균이라 총량은
     * loops를 곱해야 한다 — 이 오독은 DeepAnalyzer가 처리한다(TREE 출력을 정규식으로 파싱).
     *
     * 주의: EXPLAIN ANALYZE는 MySQL 8.4에서도 TREE 포맷만 지원한다. FORMAT=JSON은
     * "doesn't yet support 'EXPLAIN ANALYZE with JSON format'"로 거부되므로(실측 확인, 2026-07-05)
     * ai-analysis-rules.md의 JSON 표기와 달리 여기서는 실제로 동작하는 TREE 포맷을 쓴다(위장 대신 실동작).
     *
     * 안전: SELECT 전용 힌트 MAX_EXECUTION_TIME(ms)를 SELECT 바로 뒤에 주입해 실행을 상한한다
     * (이 힌트는 read-only SELECT에만 먹는다 — requireSelect가 선행 전제). 여러 행으로 와도 줄바꿈으로 합친다.
     */
    @Override
    public String explainAnalyze(String sql) {
        requireSelect(sql);
        // "select" 다음 위치에 옵티마이저 힌트를 끼운다: SELECT /*+ MAX_EXECUTION_TIME(ms) */ ...
        String timed = sql.replaceFirst("(?i)^\\s*select",
                "SELECT /*+ MAX_EXECUTION_TIME(" + DEEP_DIAGNOSIS_TIMEOUT_MS + ") */");
        try {
            return jdbc().query("EXPLAIN ANALYZE " + timed, rs -> {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(rs.getString(1));
                }
                return sb.toString();
            });
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL EXPLAIN ANALYZE 실패(8.0.18+ 필요·권한/타임아웃 확인): "
                    + e.getMessage(), e);
        }
    }

    /**
     * 대기 이벤트 — performance_schema.events_waits_summary_global_by_event_name (서버 기동 이후 누적).
     *
     * 보이는 범위는 setup_instruments에 달려 있다: 기본값은 wait/io·wait/lock만 켜져 있고
     * wait/synch(뮤텍스·rwlock 342종)는 꺼져 있다 — 대상 컨테이너에서 실측 확인(2026-07-04).
     * 그래서 이 결과는 "IO/Lock 중심의 부분 뷰"이며, 그 사실을 응답에 안내 행으로 정직하게 싣는다.
     * UPDATE performance_schema.setup_instruments로 동적 활성이 가능하지만 그것은 대상 DB의
     * 설정 변경이다 — 관제 도구는 읽기만 한다는 원칙(AGENTS.md)에 따라 여기서는 하지 않는다.
     */
    @Override
    public List<WaitEvent> waitEvents(int limit) {
        // TIMER_WAIT 계열은 피코초 단위 -> 1e9로 나눠 ms. idle은 클라이언트가 안 보내고 노는 시간이라 제외
        String sql = """
                SELECT EVENT_NAME, COUNT_STAR,
                       SUM_TIMER_WAIT / 1000000000 AS total_ms
                FROM performance_schema.events_waits_summary_global_by_event_name
                WHERE COUNT_STAR > 0
                  AND EVENT_NAME <> 'idle'
                ORDER BY SUM_TIMER_WAIT DESC
                LIMIT ?
                """;
        // 두 문장(대기 집계 + 비활성 instrument 수)은 세션 지역 상태를 공유하지 않으므로 각각 JdbcTemplate으로 실행한다
        try {
            List<WaitEvent> result = new ArrayList<>(jdbc().query(sql,
                    (rs, i) -> {
                        String name = rs.getString("EVENT_NAME");
                        return new WaitEvent(name, mysqlWaitCategory(name),
                                rs.getLong("COUNT_STAR"), rs.getDouble("total_ms"));
                    },
                    limit));
            // 꺼진 instrument 계열 수를 세서 "부분 뷰"임을 데이터로 알린다 — 없는 대기를 없다고 오독하지 않게
            Long disabled = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.setup_instruments "
                            + "WHERE NAME LIKE 'wait/%' AND ENABLED = 'NO'", Long.class);
            if (disabled != null && disabled > 0) {
                result.add(new WaitEvent(
                        "(안내) 비활성 wait instrument " + disabled
                                + "종은 집계에 없음 — setup_instruments 기본값 기준의 부분 뷰",
                        "INFO", 0, 0));
            }
            return result;
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 대기 이벤트 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 이벤트 이름의 둘째 구획이 곧 분류다 — wait/io/file/... -> io, wait/lock/table/... -> lock */
    private static String mysqlWaitCategory(String eventName) {
        String[] parts = eventName.split("/");
        return parts.length >= 2 ? parts[1] : eventName;
    }

    /**
     * 활성 세션 + 블로킹 관계 — information_schema.PROCESSLIST가 세션 목록이고, 블로킹은
     * sys.innodb_lock_waits(성능 스키마 data_lock_waits를 사람이 읽기 좋게 감싼 뷰)에서 온다.
     * 실측 확인: sys.innodb_lock_waits는 MySQL 8.4 컨테이너에 존재(2026-07-04).
     *
     * blocked_by는 상관 서브쿼리로 waiting_pid=이 세션인 첫 blocking_pid만 뽑는다 — 한 세션이 여러
     * 락을 기다리면 lock_waits에 여러 행이 생기는데, 조인하면 세션이 중복되므로 LIMIT 1로 눌렀다.
     * COMMAND='Sleep'(놀고 있는 커넥션)은 제외한다. state=COMMAND(Query/Execute…),
     * waitEvent=STATE(예: 'Waiting for table metadata lock')로 매핑한다.
     */
    @Override
    public List<SessionInfo> activeSessions(int limit) {
        String sql = """
                SELECT p.ID AS pid, p.USER AS usr, p.COMMAND AS command, p.STATE AS state,
                       p.TIME AS time_sec, p.INFO AS query,
                       (SELECT w.blocking_pid FROM sys.innodb_lock_waits w
                         WHERE w.waiting_pid = p.ID LIMIT 1) AS blocked_by
                FROM information_schema.PROCESSLIST p
                WHERE p.COMMAND <> 'Sleep'
                  AND p.ID <> CONNECTION_ID()
                ORDER BY p.TIME DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> {
                        // wasNull()은 getLong 직후에 — 다른 컬럼을 먼저 읽으면 null 판정이 그 컬럼 기준이 된다
                        long blockedBy = rs.getLong("blocked_by");
                        Long blockedByOrNull = rs.wasNull() ? null : blockedBy;
                        return new SessionInfo(
                                rs.getLong("pid"),
                                rs.getString("usr"),
                                rs.getString("command"),
                                rs.getString("state"),
                                blockedByOrNull,
                                rs.getString("query"),
                                rs.getDouble("time_sec") * 1000);
                    },
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 세션 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 세션 종료 — KILL QUERY <id>는 실행 중 문장만 취소(force=false), KILL <id>는 세션 자체를 끊는다.
     * KILL은 바인딩 파라미터를 받지 않는 문장이라 id를 문자열로 이어 붙이지만, pid는 long이라
     * 값 자체로 인젝션이 불가능하다. 같은 커넥션에서 CONNECTION_ID()로 자기 자신인지 먼저 확인해
     * 수집 커넥션을 실수로 끊는 일을 막는다.
     */
    @Override
    public String killSession(long pid, boolean force) {
        String stmt = force ? "KILL " + pid : "KILL QUERY " + pid;
        try {
            return jdbc().execute((ConnectionCallback<String>) conn -> {
                try (Statement s = conn.createStatement()) {
                    try (ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                        if (rs.next() && rs.getLong(1) == pid) {
                            throw new OperatorException("자기 수집 커넥션은 종료할 수 없습니다 (pid=" + pid + ")");
                        }
                    }
                    s.execute(stmt);
                    return stmt + " 실행됨";
                }
            });
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 세션 종료 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 스키마 구조 — information_schema.COLUMNS(구조) + STATISTICS(인덱스). 대상 스키마의 BASE TABLE만.
     * COLUMN_TYPE은 길이·부호까지 포함한 기종 고유 표기(varchar(255), int unsigned 등)를 그대로 담아
     * diff가 실제 차이를 보게 한다. STATISTICS는 인덱스 컬럼당 한 행이라 SEQ_IN_INDEX로 순서를 보존한다.
     */
    @Override
    public SchemaSnapshot describeSchema() {
        String columnsSql = """
                SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COLUMN_TYPE,
                       c.IS_NULLABLE, c.ORDINAL_POSITION
                FROM information_schema.COLUMNS c
                JOIN information_schema.TABLES t
                  ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
                WHERE c.TABLE_SCHEMA = ? AND t.TABLE_TYPE = 'BASE TABLE'
                ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION
                """;
        // NON_UNIQUE=0이 유니크. 인덱스는 컬럼마다 한 행이라 SEQ_IN_INDEX 순서로 복합 인덱스 순서 보존.
        String indexesSql = """
                SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, NON_UNIQUE
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = ?
                ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
                """;
        try {
            List<SchemaSupport.ColumnRow> columns = jdbc().query(columnsSql,
                    (rs, i) -> new SchemaSupport.ColumnRow(
                            rs.getString("TABLE_NAME"),
                            new ColumnSchema(rs.getString("COLUMN_NAME"), rs.getString("COLUMN_TYPE"),
                                    "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                                    rs.getInt("ORDINAL_POSITION"))),
                    instance.getDbName());
            List<SchemaSupport.IndexColumnRow> indexes = jdbc().query(indexesSql,
                    (rs, i) -> new SchemaSupport.IndexColumnRow(
                            rs.getString("TABLE_NAME"), rs.getString("INDEX_NAME"),
                            rs.getString("COLUMN_NAME"), rs.getInt("NON_UNIQUE") == 0),
                    instance.getDbName());
            return SchemaSupport.build(instance.getType().name(), instance.getDbName(),
                    columns, indexes, SchemaSupport.DEFAULT_MAX_TABLES);
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 스키마 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 파라미터 — SHOW GLOBAL VARIABLES(Variable_name/Value). 단위 개념이 없어 unit=null */
    @Override
    public List<DbParameter> parameters() {
        try {
            List<DbParameter> params = jdbc().query("SHOW GLOBAL VARIABLES",
                    (rs, i) -> ParameterSupport.of(rs.getString(1), rs.getString(2), null));
            params.sort(java.util.Comparator.comparing(DbParameter::name));
            return params;
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 파라미터 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 복제 상태 — 레플리카면 SHOW REPLICA STATUS에 행이 있고, Seconds_Behind_Source가 지연이다 */
    @Override
    public ReplicationState replicationState() {
        // 두 SHOW 문은 세션 지역 상태를 공유하지 않아 각각 실행해도 결과가 같다
        try {
            ReplicationState asReplica = jdbc().query("SHOW REPLICA STATUS", rs -> {
                if (rs.next()) {
                    double lag = rs.getObject("Seconds_Behind_Source") == null
                            ? -1 : rs.getDouble("Seconds_Behind_Source");
                    return new ReplicationState("REPLICA", lag,
                            "source=" + rs.getString("Source_Host") + ":" + rs.getInt("Source_Port"));
                }
                return null;
            });
            if (asReplica != null) {
                return asReplica;
            }
            return jdbc().query("SHOW REPLICAS", rs -> {
                int replicas = 0;
                while (rs.next()) {
                    replicas++;
                }
                return replicas > 0
                        ? new ReplicationState("PRIMARY", 0, "replicas=" + replicas)
                        : new ReplicationState("STANDALONE", 0, "복제 구성 없음");
            });
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 복제 상태 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 최근 데드락 (3차 아크 D-2) — InnoDB는 SHOW ENGINE INNODB STATUS 출력의 "LATEST DETECTED DEADLOCK"
     * 섹션에 <b>가장 최근 1건</b>만 남긴다(그 이전 것은 다음 데드락이 나면 덮여 사라진다). 그래서 이 경로는
     * 설정 변경 0으로 텍스트만 읽되 최대 1건이 한계다 — 과거 전수 이력은 보장하지 않는다.
     *
     * 이 명령의 Status 컬럼은 최대 약 1MB로 잘릴 수 있어(innodb_status 출력 상한) 앞뒤가 잘린 부분 출력이
     * 올 수 있고, 파싱은 그 경우에도 예외 없이 가능한 필드만 채운다. 실행에는 PROCESS 권한이 필요하다.
     */
    @Override
    public List<DeadlockEvent> recentDeadlocks(int limit) {
        if (limit < 1) {
            return List.of();
        }
        try {
            // SHOW ENGINE INNODB STATUS는 결과 한 행에 Type/Name/Status 3컬럼이고 Status에 전체 상태 텍스트가 담긴다.
            // PreparedStatement보다 plain 실행이 무난한 관리 문장이라 파라미터 없이 던진다(읽기 전용).
            String status = jdbc().query("SHOW ENGINE INNODB STATUS", rs -> {
                if (!rs.next()) {
                    return null;
                }
                // 드라이버 라벨은 'Status'지만, 방어적으로 라벨 실패 시 컬럼 인덱스 3으로 폴백한다.
                try {
                    return rs.getString("Status");
                } catch (SQLException labelMiss) {
                    return rs.getString(3);
                }
            });
            return parseLatestDeadlock(status);
        } catch (DataAccessException e) {
            throw new OperatorException(
                    "MySQL 데드락 조회 실패(SHOW ENGINE INNODB STATUS — PROCESS 권한 필요): " + e.getMessage(), e);
        }
    }

    /** 데드락 텍스트에서 뽑은 SQL/요약 문자열의 최대 길이. 원문이 길면 이 길이로 잘라 담는다. */
    private static final int DEADLOCK_TEXT_MAX = 200;

    /** 데드락 블록 상단의 발생 시각 라인(예: "2024-01-15 10:30:00 0x7f..."). */
    private static final Pattern DEADLOCK_TS = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    /** "*** WE ROLL BACK TRANSACTION (N)" — victim(롤백당한) 트랜잭션 번호. */
    private static final Pattern ROLLBACK_TXN = Pattern.compile("\\*\\*\\* WE ROLL BACK TRANSACTION \\((\\d+)\\)");

    /** 락 대상 요약 — "index <이름> of table <스키마.테이블>" (WAITING/HOLDS 락 라인에서 공통). */
    private static final Pattern LOCK_TARGET = Pattern.compile("index (\\S+) of table (\\S+)");

    /**
     * SHOW ENGINE INNODB STATUS 출력에서 최근 데드락 1건을 파싱한다(JDBC 없이 테스트 가능하도록 분리).
     *
     * <p>"LATEST DETECTED DEADLOCK" 헤더가 없으면 데드락 미발생(정상 다수 케이스)이라 빈 목록을 돌려준다.
     * 있으면 그 헤더부터 다음 "TRANSACTIONS" 섹션까지를 블록으로 잘라, 발생 시각·두 트랜잭션의 SQL·
     * victim(롤백 트랜잭션)·경합 리소스(락 대상)를 뽑는다. 특정 라인이 없으면 그 필드만 null/빈값으로 두고
     * 예외는 던지지 않는다(부분/절단 출력 방어). 최대 1건.
     */
    static List<DeadlockEvent> parseLatestDeadlock(String status) {
        if (status == null) {
            return List.of();
        }
        int header = status.indexOf("LATEST DETECTED DEADLOCK");
        if (header < 0) {
            return List.of(); // 데드락 미발생 — 흔한 정상 케이스
        }
        // 헤더 다음 "TRANSACTIONS" 섹션(복수형 — 데드락 블록의 "TRANSACTION:" 단수와 구분됨) 전까지가 블록.
        int end = status.indexOf("TRANSACTIONS", header + "LATEST DETECTED DEADLOCK".length());
        String block = end < 0 ? status.substring(header) : status.substring(header, end);

        String detectedAt = null;
        Matcher ts = DEADLOCK_TS.matcher(block);
        if (ts.find()) {
            detectedAt = ts.group(1);
        }

        String sql1 = extractTxnSql(block, 1);
        String sql2 = extractTxnSql(block, 2);
        List<String> statements = new ArrayList<>(2);
        if (sql1 != null) {
            statements.add(sql1);
        }
        if (sql2 != null) {
            statements.add(sql2);
        }

        String victim = null;
        Matcher rb = ROLLBACK_TXN.matcher(block);
        if (rb.find()) {
            String n = rb.group(1);
            String victimSql = "1".equals(n) ? sql1 : ("2".equals(n) ? sql2 : null);
            victim = "트랜잭션 (" + n + ") 롤백"
                    + (victimSql != null ? " — " + cut(victimSql, 80) : "");
        }

        // WAITING/HOLDS 락 라인에서 index·table을 모아 중복 제거해 경합 리소스 요약을 만든다.
        Set<String> targets = new LinkedHashSet<>();
        Matcher lm = LOCK_TARGET.matcher(block);
        while (lm.find()) {
            targets.add("index " + lm.group(1) + " of table " + lm.group(2));
        }
        String resource = targets.isEmpty() ? null : cut(String.join("; ", targets), DEADLOCK_TEXT_MAX);

        return List.of(new DeadlockEvent(detectedAt, statements, victim, resource, "MySQL INNODB STATUS"));
    }

    /**
     * 데드락 블록에서 "*** (N) TRANSACTION:" 마커가 가리키는 트랜잭션의 실행 SQL을 뽑는다.
     * InnoDB 출력에서 SQL은 "MySQL thread id ..., query id ..., ... &lt;state&gt;" 라인 <b>다음 줄들</b>에
     * 오고, 다음 "*** " 마커 전까지가 그 SQL이다(여러 줄일 수 있어 공백으로 합친다). 없으면 null.
     */
    private static String extractTxnSql(String block, int n) {
        String marker = "*** (" + n + ") TRANSACTION:";
        int start = block.indexOf(marker);
        if (start < 0) {
            return null;
        }
        // 이 트랜잭션 구획의 끝 = 다음 "*** " 마커 라인(WAITING/HOLDS/다음 트랜잭션) 또는 블록 끝.
        int next = block.indexOf("\n*** ", start + marker.length());
        String region = next < 0 ? block.substring(start) : block.substring(start, next);

        int mt = region.indexOf("MySQL thread id");
        if (mt < 0) {
            return null; // thread id 라인이 없으면 SQL 위치를 특정할 수 없음
        }
        int sqlStart = region.indexOf('\n', mt);
        if (sqlStart < 0) {
            return null; // thread id 라인 뒤에 SQL이 붙지 않은 절단 출력
        }
        // 여러 줄 SQL을 공백 한 칸으로 합쳐 정리한 뒤 길이 상한으로 자른다.
        String sql = region.substring(sqlStart + 1).trim().replaceAll("\\s+", " ");
        return sql.isEmpty() ? null : cut(sql, DEADLOCK_TEXT_MAX);
    }

    /** 문자열을 최대 길이로 자른다(초과분은 버림) — 데드락 텍스트 컷 공통 규칙. */
    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 통계 수집 건강 실측 (심화 아크 5) — digest 테이블 포화율·소실 카운터·PS 사각의 세 재료.
     * Performance_schema_digest_lost > 0이면 신규 쿼리 통계가 이미 소실 중(신규 쿼리 감지 부분 무력화),
     * prepared_statements_instances는 digest에 안 잡히는 PS 실행(EXECUTE 문으로만 집계)의 보완 소스다
     * — 이 테이블에는 SQL 원문이 남는다. 전부 읽기 전용 카탈로그 조회.
     */
    @Override
    public StatsHealth statsHealth() {
        try {
            Long rows = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.events_statements_summary_by_digest", Long.class);
            Long limit = jdbc().queryForObject("SELECT @@performance_schema_digests_size", Long.class);
            Long lost = jdbc().queryForObject(
                    "SELECT VARIABLE_VALUE FROM performance_schema.global_status "
                            + "WHERE VARIABLE_NAME = 'Performance_schema_digest_lost'", Long.class);
            // PS 실행은 digest에 EXECUTE 문으로만 남아 Top Query에서 익명 부하가 된다(실측 근거는 VERIFICATION)
            Map<String, Object> ps = jdbc().queryForMap(
                    "SELECT COUNT(*) AS cnt, COALESCE(SUM(COUNT_EXECUTE), 0) AS execs "
                            + "FROM performance_schema.prepared_statements_instances");
            return new StatsHealth(
                    rows == null ? -1 : rows,
                    limit == null ? -1 : limit,
                    lost == null ? -1 : lost,
                    ((Number) ps.get("cnt")).longValue(),
                    ((Number) ps.get("execs")).longValue(),
                    true,
                    "digest=events_statements_summary_by_digest, 소실=Performance_schema_digest_lost, "
                            + "PS=prepared_statements_instances");
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 통계 수집 건강 조회 실패: " + e.getMessage(), e);
        }
    }
}
