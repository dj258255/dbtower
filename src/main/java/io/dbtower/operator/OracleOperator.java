package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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

    public OracleOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools) {
        super(instance, pools, backupTools);
    }

    /** dbName은 서비스명(예: FREEPDB1) — Oracle은 데이터베이스가 아니라 서비스로 붙는다 */
    @Override
    protected String jdbcUrl() {
        return "jdbc:oracle:thin:@//%s:%d/%s"
                .formatted(instance.getHost(), instance.getPort(), instance.getDbName());
    }

    @Override
    protected String versionSql() {
        return "SELECT banner FROM v$version WHERE ROWNUM = 1";
    }

    @Override
    public List<QueryStat> queryStats(int limit) {
        // V$SQL은 sql_id당 child cursor가 여러 행일 수 있어 sql_id로 합산한다.
        // 자기 스키마 파싱 쿼리만 — SYS 백그라운드 잡 노이즈 제거
        String sql = """
                SELECT sql_id,
                       MAX(SUBSTR(sql_text, 1, 2000)) AS query_text,
                       SUM(executions) AS calls,
                       SUM(elapsed_time) / 1000 AS total_ms,
                       SUM(buffer_gets) AS logical_reads
                FROM v$sql
                WHERE parsing_schema_name = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
                GROUP BY sql_id
                ORDER BY SUM(elapsed_time) DESC
                FETCH FIRST ? ROWS ONLY
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new QueryStat(
                            rs.getString("sql_id"),
                            rs.getString("query_text"),
                            rs.getLong("calls"),
                            rs.getDouble("total_ms"),
                            rs.getLong("logical_reads")),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("Oracle 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        String sql = """
                SELECT SUBSTR(sql_text, 1, 2000) AS query_text,
                       elapsed_time / executions / 1000 AS avg_ms,
                       buffer_gets / executions AS avg_reads,
                       TO_CHAR(last_active_time, 'YYYY-MM-DD HH24:MI:SS') AS captured_at
                FROM v$sql
                WHERE executions > 0
                  AND parsing_schema_name = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
                ORDER BY elapsed_time / executions DESC
                FETCH FIRST ? ROWS ONLY
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new SlowQuery(
                            rs.getString("query_text"),
                            rs.getDouble("avg_ms"),
                            rs.getLong("avg_reads"),
                            rs.getString("captured_at")),
                    limit);
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
     * Oracle 백업 = 서버 사이드 API 실행 모델(DBMS_DATAPUMP 스키마 익스포트).
     * 파일은 서버(컨테이너)의 DATA_PUMP_DIR에 생기고, WAIT_FOR_JOB으로 완료까지 대기한다.
     */
    @Override
    public BackupResult backup(BackupPolicy policy) {
        if (policy.type() == BackupPolicy.BackupType.LOG) {
            throw new UnsupportedOperationException("Oracle 로그(아카이브 로그) 백업은 RMAN 영역 — FULL(Data Pump)만 지원");
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
