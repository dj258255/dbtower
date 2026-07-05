package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 어댑터.
 *
 * 통계 소스: performance_schema.events_statements_summary_by_digest
 * 주의 — digest는 max_digest_length(기본 1024B)까지만 정규화되므로, 앞부분이 같은 긴 쿼리들이
 * 하나로 뭉개질 수 있다. docker-compose에서 4096으로 늘려 운영한다. (당근 KDMS 사례와 동일 이슈)
 */
public class MySqlOperator extends AbstractJdbcOperator {

    public MySqlOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools) {
        super(instance, pools, backupTools);
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
        return "jdbc:mysql://%s:%d/%s?connectTimeout=3000&socketTimeout=15000"
                .formatted(instance.getHost(), instance.getPort(), instance.getDbName());
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

    /**
     * 레이턴시 백분위 (D4a) — MySQL 8.0+가 events_statements_summary_by_digest에 직접 계산해 두는
     * QUANTILE_95/QUANTILE_99 컬럼을 그대로 읽는다(NATIVE). 이 컬럼들은 피코초 단위라 1e9로 나눠 ms로 환산한다.
     *
     * <b>한계 — 누적 백분위:</b> 이 QUANTILE 값은 통계 리셋(서버 재기동/TRUNCATE) 이후의 <b>누적</b>이라
     * "최근 N분 윈도우 p95"가 아니다. 오래 뜬 서버일수록 과거 이력에 눌려 최근 급변을 늦게 반영한다.
     * 진짜 윈도우 백분위는 events_statements_histogram_by_digest 두 스냅샷을 차분해야 하는데(히스토그램 수집 필요),
     * 1단계인 여기서는 누적 p95/p99를 정직히 NATIVE로 돌려주고 그 한계를 응답 주석·UI 배지로 표기한다.
     */
    @Override
    public List<LatencyPercentile> latencyPercentiles(int limit) {
        String sql = """
                SELECT DIGEST, DIGEST_TEXT,
                       QUANTILE_95 / 1000000000 AS p95_ms,
                       QUANTILE_99 / 1000000000 AS p99_ms
                FROM performance_schema.events_statements_summary_by_digest
                WHERE DIGEST_TEXT IS NOT NULL
                ORDER BY SUM_TIMER_WAIT DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new LatencyPercentile(
                            rs.getString("DIGEST"),
                            rs.getString("DIGEST_TEXT"),
                            round2(rs.getDouble("p95_ms")),
                            round2(rs.getDouble("p99_ms")),
                            LatencyPercentile.NATIVE),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MySQL 레이턴시 백분위 조회 실패: " + e.getMessage(), e);
        }
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

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        // docker-compose에서 slow_query_log=ON, log_output=TABLE로 켜두어 mysql.slow_log에서 직접 조회한다
        String sql = """
                SELECT CONVERT(sql_text USING utf8mb4) AS sql_text,
                       TIME_TO_SEC(query_time) * 1000 AS elapsed_ms,
                       rows_examined,
                       start_time
                FROM mysql.slow_log
                ORDER BY start_time DESC
                LIMIT ?
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new SlowQuery(
                            rs.getString("sql_text"),
                            rs.getDouble("elapsed_ms"),
                            rs.getLong("rows_examined"),
                            rs.getString("start_time")),
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
}
