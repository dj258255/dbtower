package io.dbtower.operator.internal;

import io.dbtower.operator.model.BackupPolicy;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.model.ColumnSchema;
import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.model.DbParameter;
import io.dbtower.operator.model.IndexUsage;
import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.PartitionInfo;
import io.dbtower.operator.model.QueryStat;
import io.dbtower.operator.model.ReplicationState;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Oracle 어댑터.
 *
 * 통계 소스: V$SQL (라이브러리 캐시 기반 누적 통계 — elapsed_time은 마이크로초).
 * V$ 뷰 조회에는 SELECT_CATALOG_ROLE 등 권한이 필요해서, 다른 기종의 root/sa/postgres처럼
 * 모니터링 권한이 있는 계정으로 등록하는 것을 전제한다 (docker/oracle-init.sql 참고).
 *
 * 백업: MSSQL과 같은 서버 사이드 실행 모델 — 외부 CLI(expdp) 대신 DBMS_DATAPUMP PL/SQL API로
 * 서버가 직접 DATA_PUMP_DIR에 덤프를 쓴다. CLI가 없는 환경 제약과 비밀번호 argv 노출 문제가
 * 동시에 사라진다.
 */
public class OracleOperator extends AbstractJdbcOperator {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OracleOperator.class);

    /**
     * 통계를 볼 앱 스키마(C-4). 설정값 dbtower.oracle.app-schema에서 주입되며(팩토리가 전달), 비었으면
     * 시스템 스키마만 제외하고 모든 앱 SQL을 본다. 예전엔 모니터 계정의 CURRENT_SCHEMA로만 필터해서
     * 모니터≠앱이면 앱 SQL이 전멸했다.
     */
    private final String appSchema;

    /** 앱 스키마 미지정 시 노이즈로 제외할 Oracle 시스템 스키마들. */
    private static final String SYSTEM_SCHEMAS = "'SYS', 'SYSTEM', 'DBSNMP', 'SYSMAN'";

    public OracleOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools) {
        this(instance, pools, backupTools, null);
    }

    public OracleOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools,
                          String appSchema) {
        super(instance, pools, backupTools);
        this.appSchema = appSchema == null ? "" : appSchema.trim();
    }

    /** 앱 스키마 설정 여부. */
    private boolean hasAppSchema() {
        return !appSchema.isEmpty();
    }

    /**
     * parsing_schema_name 필터 절(C-4). 앱 스키마가 지정되면 바인딩 파라미터(=?)로 그 스키마만,
     * 비면 시스템 스키마만 NOT IN으로 제외해 앱 SQL 전체를 본다. 바인딩이 필요한 경우 args 앞머리에
     * appSchema가 온다(호출부가 limit 앞에 붙인다).
     */
    static String schemaFilterClause(boolean hasAppSchema) {
        return hasAppSchema
                ? "parsing_schema_name = ?"
                : "parsing_schema_name NOT IN (" + SYSTEM_SCHEMAS + ")";
    }

    /** SQL 바인딩 인자를 조립한다 — 앱 스키마가 있으면 그 값을 tail(예: limit) 앞에 끼운다. */
    private Object[] schemaFilterArgs(Object... tail) {
        if (!hasAppSchema()) {
            return tail;
        }
        Object[] args = new Object[tail.length + 1];
        args[0] = appSchema;
        System.arraycopy(tail, 0, args, 1, tail.length);
        return args;
    }

    /** dbName은 서비스명(예: FREEPDB1) — Oracle은 데이터베이스가 아니라 서비스로 붙는다 */
    @Override
    protected String jdbcUrl() {
        // useTls면 TCPS 프로토콜 — Oracle은 URL 파라미터가 아니라 프로토콜 지정 방식이다.
        // 비TLS는 기존 EZConnect(@//) 형태를 그대로 유지(하위 호환).
        String prefix = instance.isUseTls() ? "tcps://" : "//";
        return "jdbc:oracle:thin:@%s%s:%d/%s"
                .formatted(prefix, instance.getHost(), instance.getPort(), instance.getDbName());
    }

    @Override
    protected String versionSql() {
        return "SELECT banner FROM v$version WHERE ROWNUM = 1";
    }

    @Override
    public List<QueryStat> queryStats(int limit) {
        // V$SQL은 sql_id당 child cursor가 여러 행일 수 있어 sql_id로 합산한다.
        // 스키마 필터는 설정값 기반(C-4) — 앱 스키마 지정 시 그 스키마만, 미지정 시 시스템 스키마만 제외.
        String sql = """
                SELECT sql_id,
                       MAX(SUBSTR(sql_text, 1, 2000)) AS query_text,
                       SUM(executions) AS calls,
                       SUM(elapsed_time) / 1000 AS total_ms,
                       SUM(buffer_gets) AS logical_reads
                FROM v$sql
                WHERE %s
                GROUP BY sql_id
                ORDER BY SUM(elapsed_time) DESC
                FETCH FIRST ? ROWS ONLY
                """.formatted(schemaFilterClause(hasAppSchema()));
        try {
            return jdbc().query(sql,
                    (rs, i) -> new QueryStat(
                            rs.getString("sql_id"),
                            rs.getString("query_text"),
                            rs.getLong("calls"),
                            rs.getDouble("total_ms"),
                            rs.getLong("logical_reads")),
                    schemaFilterArgs(limit));
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        // 스키마 필터는 설정값 기반(C-4) — 모니터 CURRENT_SCHEMA 고정이면 모니터≠앱일 때 앱 SQL이 전멸한다.
        String sql = """
                SELECT SUBSTR(sql_text, 1, 2000) AS query_text,
                       elapsed_time / executions / 1000 AS avg_ms,
                       buffer_gets / executions AS avg_reads,
                       TO_CHAR(last_active_time, 'YYYY-MM-DD HH24:MI:SS') AS captured_at
                FROM v$sql
                WHERE executions > 0
                  AND %s
                ORDER BY elapsed_time / executions DESC
                FETCH FIRST ? ROWS ONLY
                """.formatted(schemaFilterClause(hasAppSchema()));
        try {
            return jdbc().query(sql,
                    (rs, i) -> new SlowQuery(
                            rs.getString("query_text"),
                            rs.getDouble("avg_ms"),
                            rs.getLong("avg_reads"),
                            rs.getString("captured_at")),
                    schemaFilterArgs(limit));
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 슬로우 쿼리 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public String explain(String sql) {
        requireSelect(sql);
        // EXPLAIN PLAN은 세션별 PLAN_TABLE(글로벌 임시 테이블)에 쓰므로 같은 커넥션에서 이어 읽어야 한다.
        // JdbcTemplate.query는 문장마다 커넥션을 새로 얻으니, 두 문장을 ConnectionCallback으로 한 커넥션에 묶는다.
        try {
            return jdbc().execute((ConnectionCallback<String>) conn -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("EXPLAIN PLAN FOR " + sql);
                    StringBuilder plan = new StringBuilder();
                    try (ResultSet rs = stmt.executeQuery(
                            "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
                        while (rs.next()) {
                            plan.append(rs.getString(1)).append('\n');
                        }
                    }
                    return plan.toString();
                }
            });
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 실행계획 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 플랜 변경 감지용 shape (plan flip) — Oracle은 v$sqlstats가 (sql_id, plan_hash_value)당 1행이라
     * <b>plan_hash_value가 곧 계획의 형태 식별자</b>다(별도 정규화가 필요 없다). queryId = sql_id.
     *
     * 무료 확정(19c 라이선스 매뉴얼): Diagnostics Pack 대상은 v$active_session_history·DBA_HIST뿐이고
     * v$sqlstats는 무료다. 함정: shared pool age-out 시 과거 플랜은 서버에서 사라지므로, 이력의
     * 단일 출처는 우리가 저장하는 PlanSnapshot이다(그래서 첫 관측을 기준선으로 남긴다).
     */
    @Override
    public Optional<String> planShapeForDigest(String queryId, String queryText) {
        try {
            return jdbc().query(
                    "SELECT plan_hash_value FROM v$sqlstats WHERE sql_id = ? ORDER BY last_active_time DESC "
                            + "FETCH FIRST 1 ROWS ONLY",
                    rs -> rs.next() ? planShapeForPhv(rs.getLong(1)) : Optional.<String>empty(),
                    queryId);
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * plan_hash_value → shape 식별자(C-5). PHV=0은 Oracle이 계획을 포착하지 못한 상태(계획 미수집)라
     * 실제 shape가 아니다. 이를 "PHV:0"으로 저장하면 다음 폴에서 진짜 PHV가 잡히는 순간 허위 플립으로
     * 오탐한다. 그래서 PHV=0이면 empty(관측 없음)로 정직하게 스킵한다.
     */
    static Optional<String> planShapeForPhv(long phv) {
        return phv == 0 ? Optional.empty() : Optional.of("PHV:" + phv);
    }

    /**
     * 실제 실행 계획 (D9) — 쿼리에 /*+ gather_plan_statistics *&#47; 힌트를 주입해 실행(행을 실제로 소진)한 뒤,
     * 같은 커넥션에서 DBMS_XPLAN.DISPLAY_CURSOR(NULL,NULL,'ALLSTATS LAST')로 E-Rows(추정) vs A-Rows(실측)를 읽는다.
     * 커서 통계는 세션에 매여 있어 실행과 조회가 같은 커넥션이어야 한다(기존 explain의 ConnectionCallback 패턴).
     * 필요한 V$ 뷰 권한은 SELECT_CATALOG_ROLE로 충분(모니터링 계정 이미 보유). Oracle A-Rows는 이미 누적 총량이라
     * loops(Starts)를 곱하지 않는다 — MySQL/PG의 loops당 평균과 다르다(DeepAnalyzer가 기종별로 구분).
     *
     * 안전: setQueryTimeout으로 실행을 상한한다. SELECT 전용(requireSelect).
     */
    @Override
    public String explainAnalyze(String sql) {
        requireSelect(sql);
        String hinted = sql.replaceFirst("(?i)^\\s*select", "SELECT /*+ gather_plan_statistics */");
        try {
            return jdbc().execute((ConnectionCallback<String>) conn -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout((int) (DEEP_DIAGNOSIS_TIMEOUT_MS / 1000));
                    // 실제 실행 — 행을 끝까지 소진해야 실행 통계가 커서에 쌓인다
                    try (ResultSet rs = stmt.executeQuery(hinted)) {
                        while (rs.next()) {
                            // 통계 수집 목적의 소진 — 값은 쓰지 않는다
                        }
                    }
                    StringBuilder plan = new StringBuilder();
                    try (ResultSet rs = stmt.executeQuery(
                            "SELECT plan_table_output FROM TABLE("
                                    + "DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'))")) {
                        while (rs.next()) {
                            plan.append(rs.getString(1)).append('\n');
                        }
                    }
                    return plan.toString();
                }
            });
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 실제 실행계획(DISPLAY_CURSOR) 실패"
                    + "(SELECT_CATALOG_ROLE·타임아웃 확인): " + e.getMessage(), e);
        }
    }

    @Override
    public List<TableStat> tableStats(int limit) {
        // num_rows는 옵티마이저 통계 기준이라 통계 수집(DBMS_STATS) 이후에만 채워진다
        String sql = """
                SELECT t.table_name,
                       NVL(t.num_rows, 0) AS row_count,
                       NVL((SELECT SUM(s.bytes) FROM user_segments s
                             WHERE s.segment_name = t.table_name), 0) AS data_bytes,
                       NVL((SELECT SUM(s.bytes) FROM user_indexes i
                             JOIN user_segments s ON s.segment_name = i.index_name
                             WHERE i.table_name = t.table_name), 0) AS index_bytes
                FROM user_tables t
                ORDER BY data_bytes DESC
                FETCH FIRST ? ROWS ONLY
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new TableStat(
                            rs.getString("table_name"),
                            rs.getLong("row_count"),
                            rs.getLong("data_bytes"),
                            rs.getLong("index_bytes")),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 테이블 통계 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 테이블 상세 (심화 아크 3) — 기본 통계 + 인덱스(네이티브 카디널리티) + 원문 DDL. 읽기 전용.
     *
     * Oracle 식별자는 보통 대문자로 저장되고 user_* 카탈로그의 table_name도 대문자다. 그래서 검증한
     * 테이블명을 대문자로 올려 카탈로그·DBMS_METADATA 조회에 쓴다(소문자로 만든 테이블은 이 경로 밖 —
     * 흔치 않아 감수). 응답에 노출하는 table은 요청 원문을 유지한다.
     *
     * engine=null: Oracle은 테이블별 스토리지 엔진 개념이 없다. num_rows·avg_row_len은 옵티마이저 통계라
     * DBMS_STATS 수집 이후에만 채워진다(tableStats와 같은 caveat). DDL은 DBMS_METADATA로 원문(NATIVE)을
     * 얻되, 권한 부족 등으로 실패하면 통계·인덱스는 살리고 ddl만 비운다(부분 성공 — 위장 없이 정직하게).
     */
    @Override
    public TableDetail tableDetail(String tableName) {
        String table = TableDetailSupport.requireIdentifier(tableName);
        String t = table.toUpperCase(); // user_* 뷰는 대문자 식별자로 저장
        try {
            // 기본 통계 — 세그먼트 바이트 합은 tableStats와 동일한 서브쿼리(데이터/인덱스 분리),
            // 생성 시각은 user_objects.created(TABLE 객체)를 TO_CHAR로 문자열화
            String headSql = """
                    SELECT NVL(t.num_rows, 0) AS row_count,
                           NVL((SELECT SUM(s.bytes) FROM user_segments s
                                 WHERE s.segment_name = t.table_name), 0) AS data_bytes,
                           NVL((SELECT SUM(s.bytes) FROM user_indexes i
                                 JOIN user_segments s ON s.segment_name = i.index_name
                                 WHERE i.table_name = t.table_name), 0) AS index_bytes,
                           NVL(t.avg_row_len, 0) AS avg_row_bytes,
                           (SELECT TO_CHAR(o.created, 'YYYY-MM-DD HH24:MI:SS') FROM user_objects o
                             WHERE o.object_type = 'TABLE' AND o.object_name = t.table_name) AS created_at
                    FROM user_tables t
                    WHERE t.table_name = ?
                    """;
            Object[] head = jdbc().query(headSql,
                    rs -> rs.next() ? new Object[]{rs.getLong("row_count"), rs.getLong("data_bytes"),
                            rs.getLong("index_bytes"), rs.getLong("avg_row_bytes"), rs.getString("created_at")} : null,
                    t);
            if (head == null) {
                return TableDetail.unsupported(table, "테이블을 찾을 수 없습니다: " + table);
            }
            List<TableDetail.IndexDetail> indexes = oracleIndexes(t);

            String baseNote = "Oracle는 테이블별 스토리지 엔진 개념이 없어 engine=null. "
                    + "행수·평균 행 길이는 옵티마이저 통계(DBMS_STATS 수집 후에만 채워짐) 기준 추정, "
                    + "카디널리티는 DISTINCT_KEYS(네이티브)";
            String ddl;
            TableDetail.DdlSource ddlSource;
            String note;
            try {
                // DBMS_METADATA.GET_DDL은 '함수'라 인자가 바인딩 가능하다(SHOW CREATE 같은 식별자 연결이 아님).
                // 결과는 CLOB — ojdbc는 getString으로 CLOB 전문을 문자열로 내주므로 그대로 읽는다(DDL 크기는 무난).
                ddl = jdbc().query("SELECT DBMS_METADATA.GET_DDL('TABLE', ?) FROM dual",
                        rs -> rs.next() ? rs.getString(1) : null, t);
                ddlSource = TableDetail.DdlSource.NATIVE;
                note = baseNote;
            } catch (DataAccessException e) {
                // DBMS_METADATA 실패(권한 등)는 전체를 실패시키지 않는다 — 통계·인덱스는 살리고 DDL만 비운다
                ddl = null;
                ddlSource = TableDetail.DdlSource.UNSUPPORTED;
                note = baseNote + ". DDL 원문 조회 실패(DBMS_METADATA 권한 부족 추정): " + e.getMessage();
            }
            return new TableDetail(table, null, (Long) head[0], (Long) head[1], (Long) head[2],
                    (Long) head[3], (String) head[4], ddl, ddlSource, indexes, note);
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 테이블 상세 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 인덱스 상세 — user_indexes + user_ind_columns. index_type(NORMAL/BITMAP 등)을 type으로,
     * uniqueness='UNIQUE'를 unique로 환산한다. 카디널리티는 DISTINCT_KEYS — 옵티마이저가 수집한
     * 네이티브 고유값이라 별도 근사가 필요 없다(선두 컬럼 n_distinct 추정 같은 우회가 불필요). 복합
     * 인덱스는 column_position 순으로 컬럼을 모은다. upperTable은 이미 대문자로 올린 식별자다.
     */
    private List<TableDetail.IndexDetail> oracleIndexes(String upperTable) {
        record IdxRow(String name, String column, boolean unique, String type, long cardinality) {
        }
        List<IdxRow> rows = jdbc().query("""
                SELECT i.index_name, ic.column_name, i.uniqueness, i.index_type,
                       NVL(i.distinct_keys, 0) AS cardinality
                FROM user_indexes i
                JOIN user_ind_columns ic ON ic.index_name = i.index_name
                WHERE i.table_name = ?
                ORDER BY i.index_name, ic.column_position
                """,
                (rs, i) -> new IdxRow(rs.getString("index_name"), rs.getString("column_name"),
                        "UNIQUE".equalsIgnoreCase(rs.getString("uniqueness")), rs.getString("index_type"),
                        rs.getLong("cardinality")),
                upperTable);
        Map<String, TableDetail.IndexDetail> byName = new LinkedHashMap<>();
        for (IdxRow r : rows) {
            byName.merge(r.name(),
                    new TableDetail.IndexDetail(r.name(), List.of(r.column()), r.unique(), r.type(), r.cardinality()),
                    (a, b) -> {
                        List<String> cols = new ArrayList<>(a.columns());
                        cols.addAll(b.columns());
                        // distinct_keys는 인덱스 단위 값이라 모든 행이 같다 — 첫 값을 유지
                        return new TableDetail.IndexDetail(a.name(), List.copyOf(cols), a.unique(), a.type(),
                                a.cardinality());
                    });
        }
        return List.copyOf(byName.values());
    }

    /**
     * 파티션 조회 (D5) — user_tab_partitions(파티션별 경계·행수)에 user_part_tables(파티션 방식)를 붙인다.
     * partitioning_type이 RANGE/LIST/HASH, high_value가 이 파티션의 상한 경계(LONG 컬럼 — 문자열로 읽는다).
     * 파티션 키 컬럼은 user_part_key_columns에서, 크기는 user_segments의 파티션별 바이트 합에서 얻는다.
     * 현재 접속 스키마(USER_*) 소유 객체만 본다. 읽기 전용.
     */
    @Override
    public List<PartitionInfo> partitions(int limit) {
        String sql = """
                SELECT tp.table_name,
                       tp.partition_name,
                       pt.partitioning_type AS method,
                       (SELECT LISTAGG(kc.column_name, ',') WITHIN GROUP (ORDER BY kc.column_position)
                          FROM user_part_key_columns kc WHERE kc.name = tp.table_name) AS part_expr,
                       tp.high_value AS boundary,
                       tp.num_rows AS row_count,
                       (SELECT SUM(s.bytes) FROM user_segments s
                         WHERE s.segment_name = tp.table_name
                           AND s.partition_name = tp.partition_name) AS size_bytes
                FROM user_tab_partitions tp
                JOIN user_part_tables pt ON pt.table_name = tp.table_name
                ORDER BY tp.table_name, tp.partition_position
                FETCH FIRST ? ROWS ONLY
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new PartitionInfo(
                            rs.getString("table_name"),
                            rs.getString("partition_name"),
                            rs.getString("method"),
                            rs.getString("part_expr"),
                            rs.getString("boundary"),
                            rs.getObject("row_count", Long.class),
                            rs.getObject("size_bytes", Long.class)),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 파티션 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 인덱스 사용 통계 (D6) — Oracle은 인덱스 사용 추적을 신뢰성 있게 얻기 어렵다. 12.1까지는
     * ALTER INDEX ... MONITORING USAGE를 인덱스마다 켠 뒤 v$object_usage를 봐야 하고, 12.2+의 자동
     * 추적(dba_index_usage / v$index_usage_info)도 표본 기반에 주기적 플러시라 즉시성이 없고 조회 권한도
     * 보장되지 않는다. 그래서 "미사용 인덱스 후보"를 실측 통계로 정직하게 낼 수 없어 UNSUPPORTED로 표기한다
     * (지원 위장 금지). 구조 기반 중복·잉여 인덱스 후보(D2 describeSchema)는 Oracle에서도 여전히 유효하다.
     */
    @Override
    public List<IndexUsage> indexUsage(int limit) {
        return List.of(IndexUsage.unsupported(instance.getType()
                + " 인덱스 사용 통계 미지원 — 인덱스 사용 추적(MONITORING USAGE / 자동 추적)이 기본 활성도, "
                + "조회 권한 보장도 아니라 미사용 판정을 실측으로 낼 수 없다. 구조 기반 중복·잉여 인덱스 후보는 유효."));
    }

    /**
     * Oracle 백업 = 서버 사이드 API 실행 모델(DBMS_DATAPUMP 스키마 익스포트).
     * 파일은 서버(컨테이너)의 DATA_PUMP_DIR에 생기고, WAIT_FOR_JOB으로 완료까지 대기한다.
     */
    @Override
    public BackupResult backup(BackupPolicy policy) {
        if (policy.type() == BackupPolicy.BackupType.LOG) {
            return archiveLogBackup();
        }
        String dumpFile = "oracle-%s-%s.dmp"
                .formatted(safeFileName(instance.getName()), backupTimestamp());
        String block = """
                DECLARE
                  h NUMBER;
                  state VARCHAR2(30);
                BEGIN
                  h := DBMS_DATAPUMP.OPEN(operation => 'EXPORT', job_mode => 'SCHEMA');
                  DBMS_DATAPUMP.ADD_FILE(h, ?, 'DATA_PUMP_DIR');
                  DBMS_DATAPUMP.START_JOB(h);
                  DBMS_DATAPUMP.WAIT_FOR_JOB(h, state);
                END;
                """;
        try (Connection conn = open(); CallableStatement cs = conn.prepareCall(block)) {
            cs.setString(1, dumpFile);
            cs.execute();
            return new BackupResult("(server) DATA_PUMP_DIR/" + dumpFile, -1); // 서버 측 파일이라 크기 조회 생략
        } catch (SQLException e) {
            throw new OperatorException("Oracle 백업 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 로그 백업 (Phase 2) = ALTER SYSTEM ARCHIVE LOG CURRENT(아카이빙 완료까지 대기 — 백업 스크립트의
     * 표준 관행, 공식 문서)로 현재 리두를 아카이브시킨 뒤, V$ARCHIVED_LOG의 최신 아카이브 파일을 수집한다.
     * RMAN이 정석이지만 O/S 유틸리티로 아카이브 파일을 복사하는 방식도 공식 문서가 인정하는 방법이다.
     *
     * 게이트(정직): NOARCHIVELOG 모드면 아카이브가 생성되지 않는다 — 모드 전환은 mount 재기동이 필요한
     * 대상 서버 구성이라 우리가 하지 않는다(UNSUPPORTED + 사유). ALTER SYSTEM 권한 부족도 동일 취급.
     */
    private BackupResult archiveLogBackup() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            String mode;
            try (ResultSet rs = st.executeQuery("SELECT log_mode FROM v$database")) {
                rs.next();
                mode = rs.getString(1);
            }
            if (!"ARCHIVELOG".equalsIgnoreCase(mode)) {
                throw new UnsupportedOperationException(
                        "NOARCHIVELOG 모드 — 아카이브 로그가 생성되지 않는다. 모드 전환은 mount 재기동이 "
                                + "필요한 대상 서버 구성이라 우리가 바꾸지 않는다(ARCHIVELOG면 로그 백업 동작)");
            }
            if (backupTools.oracleArchiveCommand() == null || backupTools.oracleArchiveCommand().isBlank()) {
                throw new UnsupportedOperationException(
                        "아카이브 수집 명령 미설정(dbtower.backup.oracle-archivelog-command) — 정석은 RMAN "
                                + "BACKUP ARCHIVELOG ALL이며, 파일 복사 수집도 공식 문서가 인정하는 방식이다");
            }
            try {
                st.execute("ALTER SYSTEM ARCHIVE LOG CURRENT");
            } catch (SQLException e) {
                // best-effort로 강등(실측 교훈): PDB 접속은 ORA-65040(아카이브 전환은 CDB 수준 작업),
                // 권한 부족은 ORA-01031 — 어느 쪽이든 "경계를 지금 만들지 못할" 뿐, 이미 아카이브된
                // 최신 파일 수집은 가능하다. 운영 DB는 로그 스위치가 자연 발생하므로 수집은 유효하다.
                LOG.info("Oracle ARCHIVE LOG CURRENT 생략(경계 없이 기존 아카이브 수집): {}", e.getMessage());
            }
            // 정석(현업 스크립트): 최신 하나가 아니라 미수집 아카이브 전부를 보충 수집한다(멱등) —
            // 주기 사이에 로그 스위치가 여러 번 일어나면 체인에 구멍이 나기 때문. 첫 실행 폭주 방지 상한 50.
            List<String> archives = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT name FROM (SELECT name FROM v$archived_log WHERE name IS NOT NULL "
                            + "ORDER BY completion_time ASC) WHERE ROWNUM <= 50")) {
                while (rs.next()) {
                    archives.add(rs.getString(1));
                }
            }
            if (archives.isEmpty()) {
                throw new OperatorException(
                        "아카이브된 로그가 아직 없습니다 — 로그 스위치(서버 활동 또는 CDB에서 ARCHIVE LOG CURRENT) 후 생성됩니다");
            }
            long totalBytes = 0;
            String lastLocation = null;
            int collected = 0;
            for (String archived : archives) {
                // 수집 명령은 접두부 템플릿 + 서버 관점 절대경로 인자(다른 기종과 같은 모델)
                String baseName = safeFileName(archived.substring(archived.lastIndexOf('/') + 1));
                if (alreadyCollected(baseName)) {
                    continue;
                }
                Path out = Path.of(backupTools.backupDir(),
                        "oracle-archivelog-%s-%s-%s".formatted(
                                safeFileName(instance.getName()), backupTimestamp(), baseName));
                List<String> cmd = new ArrayList<>(renderCommand(backupTools.oracleArchiveCommand()));
                cmd.add(archived);
                BackupResult one = runCliBackup(cmd, Map.of(), out);
                totalBytes += Math.max(0, one.bytes());
                lastLocation = one.location();
                collected++;
            }
            if (collected == 0) {
                throw new OperatorException("수집할 새 아카이브 로그가 없습니다(전부 이미 수집됨)");
            }
            return new BackupResult(lastLocation, totalBytes);
        } catch (SQLException e) {
            throw new OperatorException("Oracle 아카이브 로그 백업 실패: " + e.getMessage(), e);
        }
    }

    /** 이 아카이브 파일이 이미 로컬 산출물로 수집됐는가 — 파일명 접미로 판정(멱등 수집). */
    private boolean alreadyCollected(String baseName) {
        try (var stream = Files.list(Path.of(backupTools.backupDir()))) {
            return stream.anyMatch(p -> p.getFileName().toString().startsWith("oracle-archivelog")
                    && p.getFileName().toString().endsWith("-" + baseName));
        } catch (java.io.IOException e) {
            return false;   // 조회 실패면 재수집(중복이 유실보다 낫다)
        }
    }

    /**
     * Oracle 복원 검증 = UNSUPPORTED (정직한 한계 표시).
     * Data Pump 덤프는 서버 측 DATA_PUMP_DIR에 생기고 플랫폼이 파일에 접근하지 못한다.
     * 자동 복원 검증을 하려면 임시 스키마로의 IMPDP(DBMS_DATAPUMP IMPORT) 실행과 그 스키마의
     * 사후 정리·권한이 필요해 현재 범위 밖 — 여기서는 "확인 못 함"을 통과로 위장하지 않는다.
     * (MSSQL의 RESTORE VERIFYONLY 같은 단발 무결성 검증 API가 Data Pump에는 없다.)
     */
    @Override
    public RestoreVerification verifyRestore(String location) {
        return RestoreVerification.unsupported(
                "Oracle Data Pump 덤프는 서버 측 산출물 — 임시 스키마 IMPDP가 필요해 자동 복원 검증 범위 밖: "
                        + location);
    }

    /**
     * 대기 이벤트 — v$system_event (인스턴스 기동 이후 누적). Oracle은 wait_class라는
     * 공식 분류(User I/O, Concurrency, Scheduler, ...)를 뷰가 직접 제공해서 그대로 category로 쓴다.
     * 'Idle' 클래스는 세션이 일 없이 기다린 시간(예: SQL*Net message from client)이라 제외 —
     * 다른 기종에서 idle 계열을 걸러내는 것과 같은 이유다.
     */
    @Override
    public List<WaitEvent> waitEvents(int limit) {
        // time_waited_micro는 마이크로초 -> 1000으로 나눠 ms
        String sql = """
                SELECT event, wait_class, total_waits,
                       time_waited_micro / 1000 AS total_ms
                FROM v$system_event
                WHERE wait_class != 'Idle'
                ORDER BY time_waited_micro DESC
                FETCH FIRST ? ROWS ONLY
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new WaitEvent(
                            rs.getString("event"),
                            rs.getString("wait_class"),
                            rs.getLong("total_waits"),
                            rs.getDouble("total_ms")),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 대기 이벤트 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 활성 세션 + 블로킹 관계 — v$session에서 지금 일하거나(status='ACTIVE') 남에게 막힌 세션만.
     * blocking_session이 곧 나를 막는 세션의 sid(막힘 없으면 null)라 락 뷰를 조인할 필요가 없다.
     * 쿼리는 v$sql을 sql_id로 조인해 가져온다(현재/직전 문장). last_call_et는 초 → ms로 환산.
     * pid는 sid로 다룬다 — kill 때 필요한 serial#은 그 시점에 재조회한다(SessionInfo.pid=sid).
     * 자기 조회 세션은 SYS_CONTEXT SID로 제외.
     */
    @Override
    public List<SessionInfo> activeSessions(int limit) {
        String sql = """
                SELECT s.sid AS pid, s.username AS usr, s.status AS state,
                       s.event AS wait_event, s.blocking_session AS blocked_by,
                       SUBSTR(q.sql_text, 1, 2000) AS query,
                       s.last_call_et * 1000 AS elapsed_ms
                FROM v$session s
                LEFT JOIN v$sql q ON q.sql_id = s.sql_id AND q.child_number = s.sql_child_number
                WHERE s.type = 'USER'
                  AND s.username IS NOT NULL
                  AND s.sid <> SYS_CONTEXT('USERENV', 'SID')
                  AND (s.status = 'ACTIVE' OR s.blocking_session IS NOT NULL)
                ORDER BY s.last_call_et DESC
                FETCH FIRST ? ROWS ONLY
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
                                rs.getString("state"),
                                rs.getString("wait_event"),
                                blockedByOrNull,
                                rs.getString("query"),
                                rs.getDouble("elapsed_ms"));
                    },
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 세션 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 세션 종료 — ALTER SYSTEM KILL SESSION 'sid,serial#' [IMMEDIATE]. Oracle의 kill 대상은
     * sid만으로 부족하고 serial#이 함께 필요해(그 사이 sid 재사용 방지), 넘어온 sid로 serial#을
     * 지금 재조회한다. force=true면 IMMEDIATE(즉시 롤백·정리), 아니면 표준 종료.
     * sid/serial#은 재조회한 숫자라 값으로 인젝션이 불가능하다. ALTER SYSTEM 권한이 없으면
     * 그 사실이 드러나는 명확한 에러로 감싼다. 자기 세션(SYS_CONTEXT SID)은 거부한다.
     */
    @Override
    public String killSession(long pid, boolean force) {
        try {
            Long mySid = jdbc().queryForObject(
                    "SELECT TO_NUMBER(SYS_CONTEXT('USERENV', 'SID')) FROM dual", Long.class);
            if (mySid != null && mySid == pid) {
                throw new OperatorException("자기 수집 세션은 종료할 수 없습니다 (sid=" + pid + ")");
            }
            List<Long> serials = jdbc().query(
                    "SELECT serial# AS serial_no FROM v$session WHERE sid = ?",
                    (rs, i) -> rs.getLong("serial_no"), pid);
            if (serials.isEmpty()) {
                throw new OperatorException("종료할 세션을 찾을 수 없습니다 (sid=" + pid + ")");
            }
            String stmt = "ALTER SYSTEM KILL SESSION '%d,%d'%s"
                    .formatted(pid, serials.get(0), force ? " IMMEDIATE" : "");
            jdbc().execute(stmt);
            return stmt + " 실행됨";
        } catch (DataAccessException e) {
            throw new OperatorException(
                    "Oracle 세션 종료 실패(ALTER SYSTEM 권한 필요): " + e.getMessage(), e);
        }
    }

    /**
     * 스키마 구조 — 컬럼은 user_tab_columns, 인덱스는 user_indexes + user_ind_columns(현재 스키마).
     * 뷰의 컬럼이 섞이지 않게 user_tables에 있는 것만 남긴다. nullable은 'Y'/'N', uniqueness는
     * 'UNIQUE'/'NONUNIQUE'로 오므로 각각 boolean으로 환산한다. column_id/column_position이 순서다.
     * data_type은 길이 없이 VARCHAR2처럼만 와서, data_length를 붙여 기종 표기에 가깝게 만든다.
     */
    @Override
    public SchemaSnapshot describeSchema() {
        String columnsSql = """
                SELECT c.table_name,
                       c.column_name,
                       CASE WHEN c.data_type IN ('VARCHAR2', 'CHAR', 'NVARCHAR2', 'RAW')
                            THEN c.data_type || '(' || c.data_length || ')'
                            ELSE c.data_type END AS column_type,
                       c.nullable,
                       c.column_id
                FROM user_tab_columns c
                WHERE c.table_name IN (SELECT table_name FROM user_tables)
                ORDER BY c.table_name, c.column_id
                """;
        String indexesSql = """
                SELECT i.table_name, i.index_name, ic.column_name, i.uniqueness
                FROM user_indexes i
                JOIN user_ind_columns ic ON ic.index_name = i.index_name
                WHERE i.table_name IN (SELECT table_name FROM user_tables)
                ORDER BY i.table_name, i.index_name, ic.column_position
                """;
        try {
            List<SchemaSupport.ColumnRow> columns = jdbc().query(columnsSql,
                    (rs, i) -> new SchemaSupport.ColumnRow(
                            rs.getString("table_name"),
                            new ColumnSchema(rs.getString("column_name"), rs.getString("column_type"),
                                    "Y".equalsIgnoreCase(rs.getString("nullable")),
                                    rs.getInt("column_id"))));
            List<SchemaSupport.IndexColumnRow> indexes = jdbc().query(indexesSql,
                    (rs, i) -> new SchemaSupport.IndexColumnRow(
                            rs.getString("table_name"), rs.getString("index_name"),
                            rs.getString("column_name"), "UNIQUE".equalsIgnoreCase(rs.getString("uniqueness"))));
            return SchemaSupport.build(instance.getType().name(), instance.getDbName(),
                    columns, indexes, SchemaSupport.DEFAULT_MAX_TABLES);
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 스키마 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 파라미터 — v$parameter(name/value). 접근하려면 계정에 v_$parameter SELECT 권한이 필요하다 */
    @Override
    public List<DbParameter> parameters() {
        try {
            return jdbc().query("SELECT name, value FROM v$parameter ORDER BY name",
                    (rs, i) -> ParameterSupport.of(rs.getString("name"), rs.getString("value"), null));
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 파라미터 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 복제 상태 — Data Guard 기준의 데이터베이스 역할. 미구성 단독 인스턴스도 PRIMARY로 표시된다 */
    @Override
    public ReplicationState replicationState() {
        String sql = "SELECT database_role, open_mode, protection_mode FROM v$database";
        try {
            return jdbc().query(sql, rs -> {
                if (rs.next()) {
                    return new ReplicationState(
                            rs.getString("database_role"),
                            -1,
                            "open_mode=%s protection=%s".formatted(
                                    rs.getString("open_mode"), rs.getString("protection_mode")));
                }
                return new ReplicationState("UNKNOWN", -1, "v$database 조회 결과 없음");
            });
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 복제 상태 조회 실패: " + e.getMessage(), e);
        }
    }
}
