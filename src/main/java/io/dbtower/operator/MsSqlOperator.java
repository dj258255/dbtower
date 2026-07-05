package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import org.springframework.dao.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SQL Server 어댑터.
 *
 * 통계 소스: sys.dm_exec_query_stats DMV (플랜 캐시 기반 누적 통계)
 * 실행계획: 임의 쿼리 실행 대신 플랜 캐시(sys.dm_exec_query_plan)에서 조회한다 —
 * 운영 DB에 부하를 주지 않고 실제 사용된 플랜을 보는 방식.
 */
public class MsSqlOperator extends AbstractJdbcOperator {

    public MsSqlOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools) {
        super(instance, pools, backupTools);
    }

    /**
     * SQL Server 백업 = 서버 사이드 SQL 실행 모델(BACKUP DATABASE).
     * 외부 도구 없이 SQL만으로 서버가 직접 파일을 쓴다 — 경로도 서버(컨테이너) 기준.
     */
    @Override
    public BackupResult backup(BackupPolicy policy) {
        String serverPath = "/var/opt/mssql/data/%s-%s.bak"
                .formatted(safeFileName(instance.getName()), backupTimestamp());
        // 식별자는 바인딩이 안 되므로 ]를 ]]로 이스케이프해 대괄호 탈출을 막는다 (등록 시 패턴 검증 + 심층 방어)
        String escapedDb = instance.getDbName().replace("]", "]]");
        String sql = policy.type() == BackupPolicy.BackupType.LOG
                ? "BACKUP LOG [%s] TO DISK = ?".formatted(escapedDb)
                : "BACKUP DATABASE [%s] TO DISK = ?".formatted(escapedDb);
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serverPath);
            ps.execute();
            return new BackupResult("(server) " + serverPath, -1); // 서버 측 파일이라 크기는 조회 생략
        } catch (SQLException e) {
            throw new OperatorException("MSSQL 백업 실패: " + e.getMessage(), e);
        }
    }

    /**
     * SQL Server 복원 검증 = RESTORE VERIFYONLY (서버 사이드 T-SQL).
     * 백업이 서버 측 파일(.bak)이라 플랫폼이 파일에 직접 접근하지 못한다 — 전체 복원은 범위 밖.
     * 대신 서버가 백업셋의 완전성/판독성(헤더·페이지·체크섬)을 확인하는 VERIFYONLY까지가
     * 여기서 정직하게 할 수 있는 최선이다. "복원 가능성의 신호"이지 전체 데이터 재구성은 아니다.
     */
    @Override
    public RestoreVerification verifyRestore(String location) {
        // backup()이 "(server) <path>"로 돌려주므로 접두사를 떼어 실제 경로만 남긴다
        String path = location.startsWith("(server) ") ? location.substring("(server) ".length()) : location;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("RESTORE VERIFYONLY FROM DISK = ?")) {
            ps.setString(1, path);
            ps.execute();
            return RestoreVerification.verified(
                    "RESTORE VERIFYONLY 통과 — 백업셋 판독/완전성 확인(전체 복원 아님): " + path, null);
        } catch (SQLException e) {
            return RestoreVerification.failed("RESTORE VERIFYONLY 실패: " + e.getMessage());
        }
    }

    @Override
    protected String jdbcUrl() {
        return "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;loginTimeout=3"
                .formatted(instance.getHost(), instance.getPort(), instance.getDbName());
    }

    @Override
    protected String versionSql() {
        return "SELECT @@VERSION";
    }

    @Override
    public List<QueryStat> queryStats(int limit) {
        // total_elapsed_time은 마이크로초 단위라 1000으로 나눠 ms로 환산한다
        String sql = """
                SELECT TOP (?)
                       CONVERT(varchar(64), qs.query_hash, 1) AS query_id,
                       SUBSTRING(st.text, 1, 2000) AS query_text,
                       qs.execution_count,
                       qs.total_elapsed_time / 1000.0 AS total_ms,
                       qs.total_logical_reads
                FROM sys.dm_exec_query_stats qs
                CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                ORDER BY qs.total_elapsed_time DESC
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new QueryStat(
                            rs.getString("query_id"),
                            rs.getString("query_text"),
                            rs.getLong("execution_count"),
                            rs.getDouble("total_ms"),
                            rs.getLong("total_logical_reads")),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SlowQuery> slowQueries(int limit) {
        String sql = """
                SELECT TOP (?)
                       SUBSTRING(st.text, 1, 2000) AS query_text,
                       qs.total_elapsed_time / qs.execution_count / 1000.0 AS avg_ms,
                       qs.total_logical_reads / qs.execution_count AS avg_reads
                FROM sys.dm_exec_query_stats qs
                CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                WHERE qs.execution_count > 0
                ORDER BY avg_ms DESC
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new SlowQuery(
                            rs.getString("query_text"),
                            rs.getDouble("avg_ms"),
                            rs.getLong("avg_reads"),
                            LocalDateTime.now().toString()),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 슬로우 쿼리 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TableStat> tableStats(int limit) {
        // used_page_count는 8KB 페이지 단위라 바이트로 환산한다
        String sql = """
                SELECT TOP (?)
                       t.name AS table_name,
                       SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.row_count ELSE 0 END) AS row_count,
                       SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.used_page_count ELSE 0 END) * 8192 AS data_bytes,
                       SUM(CASE WHEN ps.index_id > 1 THEN ps.used_page_count ELSE 0 END) * 8192 AS index_bytes
                FROM sys.dm_db_partition_stats ps
                JOIN sys.tables t ON t.object_id = ps.object_id
                GROUP BY t.name
                ORDER BY SUM(ps.used_page_count) DESC
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
            throw new OperatorException("MSSQL 테이블 통계 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 파티션 조회 (D5) — SQL Server는 파티션 함수(RANGE LEFT/RIGHT)와 파티션 스킴 기반이다.
     * sys.partitions는 모든 테이블에 최소 1행이 있으므로, 파티션 스킴(data_space type='PS')에 올라탄
     * 인덱스만 골라 진짜 파티션 테이블만 남긴다. 파티션은 이름이 아니라 번호라 partitionName은 "partition N".
     * boundary는 sys.partition_range_values에서 경계값을(RANGE RIGHT면 boundary_id=번호-1, LEFT면 번호),
     * 파티션 컬럼은 partition_ordinal=1인 인덱스 컬럼에서 얻는다. 읽기 전용.
     */
    @Override
    public List<PartitionInfo> partitions(int limit) {
        String sql = """
                SELECT TOP (?)
                       t.name AS table_name,
                       p.partition_number AS partition_number,
                       pf.name AS func_name,
                       pf.boundary_value_on_right AS on_right,
                       (SELECT c.name FROM sys.index_columns ic
                          JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                          WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id
                            AND ic.partition_ordinal = 1) AS part_expr,
                       CAST(prv.value AS nvarchar(256)) AS boundary,
                       p.rows AS row_count,
                       SUM(au.total_pages) * 8192 AS size_bytes
                FROM sys.partitions p
                JOIN sys.indexes i ON i.object_id = p.object_id AND i.index_id = p.index_id
                JOIN sys.tables t ON t.object_id = p.object_id
                JOIN sys.data_spaces ds ON ds.data_space_id = i.data_space_id AND ds.type = 'PS'
                JOIN sys.partition_schemes psch ON psch.data_space_id = ds.data_space_id
                JOIN sys.partition_functions pf ON pf.function_id = psch.function_id
                JOIN sys.allocation_units au ON au.container_id = p.hobt_id
                LEFT JOIN sys.partition_range_values prv ON prv.function_id = pf.function_id
                       AND prv.boundary_id = p.partition_number
                            - CASE WHEN pf.boundary_value_on_right = 1 THEN 1 ELSE 0 END
                WHERE i.index_id IN (0, 1)
                GROUP BY t.name, p.partition_number, pf.name, pf.boundary_value_on_right,
                         i.object_id, i.index_id, prv.value, p.rows
                ORDER BY t.name, p.partition_number
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new PartitionInfo(
                            rs.getString("table_name"),
                            "partition " + rs.getInt("partition_number"),
                            "RANGE " + (rs.getBoolean("on_right") ? "RIGHT" : "LEFT")
                                    + " (" + rs.getString("func_name") + ")",
                            rs.getString("part_expr"),
                            rs.getString("boundary"),
                            rs.getObject("row_count", Long.class),
                            rs.getObject("size_bytes", Long.class)),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 파티션 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 대기 이벤트 — sys.dm_os_wait_stats (서버 기동 이후 누적).
     *
     * 이 DMV는 백그라운드 스레드가 "일이 올 때까지 자는" 시간까지 전부 기록해서, 필터 없이는
     * SLEEP/BROKER/DISPATCHER류가 상위를 도배한다(실측: SOS_WORK_DISPATCHER 하나가 8억 ms).
     * 그래서 무해한 idle/백그라운드 대기를 제외하는 것이 정석이며, 아래 목록은 SQL Server
     * 커뮤니티에서 널리 쓰이는 벤치마크 필터(Paul Randal의 wait stats 스크립트 계열)에서
     * 이 환경에 실제로 나타난 것을 중심으로 추린 것이다. 각 항목은 "요청을 처리하느라 기다린
     * 시간"이 아니라 "할 일이 없어 기다린 시간"이라 성능 신호가 아니다.
     */
    @Override
    public List<WaitEvent> waitEvents(int limit) {
        String sql = """
                SELECT TOP (?)
                       wait_type,
                       waiting_tasks_count,
                       wait_time_ms
                FROM sys.dm_os_wait_stats
                WHERE waiting_tasks_count > 0
                  AND wait_type NOT IN (
                      'LAZYWRITER_SLEEP', 'LOGMGR_QUEUE', 'CHECKPOINT_QUEUE', 'CHKPT',
                      'REQUEST_FOR_DEADLOCK_SEARCH', 'WAITFOR', 'DIRTY_PAGE_POLL',
                      'SP_SERVER_DIAGNOSTICS_SLEEP', 'SQLTRACE_INCREMENTAL_FLUSH_SLEEP',
                      'SOS_WORK_DISPATCHER', 'DISPATCHER_QUEUE_SEMAPHORE',
                      'STARTUP_DEPENDENCY_MANAGER', 'FT_IFTS_SCHEDULER_IDLE_WAIT',
                      'AZURE_IMDS_VERSIONS', 'WAIT_XTP_HOST_WAIT')
                  AND wait_type NOT LIKE 'SLEEP[_]%'    -- 스케줄된 잠자기 (SLEEP_TASK, SLEEP_SYSTEMTASK 등)
                  AND wait_type NOT LIKE 'CLR[_]%'      -- CLR 호스트 백그라운드 이벤트 대기
                  AND wait_type NOT LIKE 'BROKER%'      -- Service Broker 대기 큐 (미사용 시도 항상 대기)
                  AND wait_type NOT LIKE 'XE[_]%'       -- Extended Events 세션의 자체 타이머
                  AND wait_type NOT LIKE 'QDS[_]%'      -- Query Store 백그라운드 플러시 루프
                  AND wait_type NOT LIKE 'HADR[_]%'     -- AlwaysOn 백그라운드 (AG 미구성이어도 발생)
                  AND wait_type NOT LIKE 'PWAIT[_]%'    -- 내부 preemptive 백그라운드 태스크
                  AND wait_type NOT LIKE 'PREEMPTIVE[_]%' -- OS 호출 구간 (쿼리 대기 아님)
                  AND wait_type NOT LIKE 'PARALLEL_REDO[_]%'
                ORDER BY wait_time_ms DESC
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> {
                        String waitType = rs.getString("wait_type");
                        return new WaitEvent(waitType, msSqlWaitCategory(waitType),
                                rs.getLong("waiting_tasks_count"), rs.getDouble("wait_time_ms"));
                    },
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 대기 이벤트 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * dm_os_wait_stats에는 Oracle의 wait_class 같은 분류 컬럼이 없어서 접두어 관례로 분류한다.
     * DBA가 화면에서 "이건 IO 문제, 저건 Lock 문제"를 한눈에 가르는 용도 — 정밀 분류가 아니다.
     */
    private static String msSqlWaitCategory(String waitType) {
        if (waitType.startsWith("LCK_")) return "Lock";
        if (waitType.startsWith("PAGEIOLATCH_") || waitType.startsWith("IO_")
                || waitType.startsWith("WRITELOG") || waitType.startsWith("ASYNC_IO")
                || waitType.startsWith("BACKUPIO")) return "IO";
        if (waitType.startsWith("PAGELATCH_") || waitType.startsWith("LATCH_")) return "Latch";
        if (waitType.startsWith("SOS_SCHEDULER_YIELD") || waitType.startsWith("CXPACKET")
                || waitType.startsWith("CXCONSUMER") || waitType.startsWith("THREADPOOL")) return "CPU";
        if (waitType.startsWith("RESOURCE_SEMAPHORE") || waitType.startsWith("MEMORY_")
                || waitType.startsWith("CMEMTHREAD")) return "Memory";
        if (waitType.startsWith("ASYNC_NETWORK_IO")) return "Network";
        return "Other";
    }

    /**
     * 활성 세션 + 블로킹 관계 — sys.dm_exec_sessions(세션)에 dm_exec_requests(현재 요청)를 붙이고,
     * blocked = blocking_session_id(0이면 막힘 없음 → NULLIF로 null). 쿼리 텍스트는 dm_exec_sql_text.
     * 지금 요청을 실행 중인 세션과, 요청이 없어도 남을 막고 있는 세션만 보인다(트랜잭션을 쥔 채 노는
     * 세션도 블로킹 원인이라 포함). is_user_process=1로 시스템 세션은 빼고, @@SPID로 자기 자신 제외.
     * total_elapsed_time은 ms 단위다.
     */
    @Override
    public List<SessionInfo> activeSessions(int limit) {
        String sql = """
                SELECT TOP (?)
                       s.session_id AS pid, s.login_name AS usr,
                       COALESCE(r.status, 'sleeping') AS state, r.wait_type AS wait_event,
                       NULLIF(r.blocking_session_id, 0) AS blocked_by,
                       SUBSTRING(t.text, 1, 2000) AS query,
                       COALESCE(r.total_elapsed_time, 0) AS elapsed_ms
                FROM sys.dm_exec_sessions s
                LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id
                OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t
                WHERE s.is_user_process = 1
                  AND s.session_id <> @@SPID
                  AND (r.session_id IS NOT NULL
                       OR EXISTS (SELECT 1 FROM sys.dm_exec_requests b
                                   WHERE b.blocking_session_id = s.session_id))
                ORDER BY elapsed_ms DESC
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
            throw new OperatorException("MSSQL 세션 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 세션 종료 — SQL Server의 KILL은 취소/강제 구분이 없다. 실행 중 문장을 롤백하고 세션을 끊는
     * 단일 동작뿐이라 force 인자는 무시한다(항상 KILL). KILL은 바인딩을 받지 않는 문장이지만
     * session_id는 long이라 값 자체로 인젝션이 불가능하다. 같은 커넥션의 @@SPID로 자기 세션은 거부.
     */
    @Override
    public String killSession(long pid, boolean force) {
        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT @@SPID")) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong(1) == pid) {
                        throw new OperatorException("자기 수집 커넥션은 종료할 수 없습니다 (SPID=" + pid + ")");
                    }
                }
            }
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("KILL " + pid); // force 무시 — SQL Server는 취소/강제 구분 없음
            }
            return "KILL " + pid + " 실행됨 (force 무시 — SQL Server는 취소/강제 구분 없음)";
        } catch (SQLException e) {
            throw new OperatorException("MSSQL 세션 종료 실패: " + e.getMessage(), e);
        }
    }

    /** 복제 상태 — AlwaysOn 가용성 그룹의 DMV. AG 미구성 단독 인스턴스면 행이 없다 */
    @Override
    public ReplicationState replicationState() {
        String sql = """
                SELECT rs.is_primary_replica, COUNT(*) OVER () AS replica_count
                FROM sys.dm_hadr_database_replica_states rs
                """;
        try {
            return jdbc().query(sql, rs -> {
                if (rs.next()) {
                    String role = rs.getBoolean("is_primary_replica") ? "PRIMARY" : "REPLICA";
                    return new ReplicationState(role, -1, "AlwaysOn replicas=" + rs.getInt("replica_count"));
                }
                return new ReplicationState("STANDALONE", 0, "AlwaysOn 가용성 그룹 미구성");
            });
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 복제 상태 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 스키마 구조 — 컬럼은 INFORMATION_SCHEMA.COLUMNS(연결된 DB 기준), 인덱스는 sys.indexes에
     * index_columns·columns를 조인해 컬럼 순서(key_ordinal)와 유니크(is_unique)를 뽑는다.
     * 시스템 테이블(is_ms_shipped=1)과 INCLUDE 컬럼(is_included_column=1)은 제외 —
     * 후자는 인덱스 키가 아니라 커버링용 부록이라 구조 diff 신호가 아니다. 힙(인덱스 없는 테이블)은
     * ind.name IS NULL이라 인덱스 목록이 빈 채로 컬럼만 잡힌다.
     */
    @Override
    public SchemaSnapshot describeSchema() {
        // DATA_TYPE만으로는 varchar(255)의 길이가 안 보여서, 길이/정밀도를 붙여 기종 표기에 가깝게 만든다
        String columnsSql = """
                SELECT TABLE_NAME, COLUMN_NAME,
                       DATA_TYPE + COALESCE('(' + CASE
                           WHEN CHARACTER_MAXIMUM_LENGTH = -1 THEN 'max'
                           WHEN CHARACTER_MAXIMUM_LENGTH IS NOT NULL
                               THEN CONVERT(varchar, CHARACTER_MAXIMUM_LENGTH)
                           WHEN DATA_TYPE IN ('decimal','numeric')
                               THEN CONVERT(varchar, NUMERIC_PRECISION) + ',' + CONVERT(varchar, NUMERIC_SCALE)
                           ELSE NULL END + ')', '') AS COLUMN_TYPE,
                       IS_NULLABLE, ORDINAL_POSITION
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA NOT IN ('sys', 'INFORMATION_SCHEMA')
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """;
        String indexesSql = """
                SELECT t.name AS table_name, ind.name AS index_name,
                       col.name AS column_name, ind.is_unique
                FROM sys.indexes ind
                JOIN sys.tables t ON t.object_id = ind.object_id
                JOIN sys.index_columns ic ON ic.object_id = ind.object_id AND ic.index_id = ind.index_id
                JOIN sys.columns col ON col.object_id = ic.object_id AND col.column_id = ic.column_id
                WHERE ind.name IS NOT NULL AND t.is_ms_shipped = 0 AND ic.is_included_column = 0
                ORDER BY t.name, ind.name, ic.key_ordinal
                """;
        try {
            List<SchemaSupport.ColumnRow> columns = jdbc().query(columnsSql,
                    (rs, i) -> new SchemaSupport.ColumnRow(
                            rs.getString("TABLE_NAME"),
                            new ColumnSchema(rs.getString("COLUMN_NAME"), rs.getString("COLUMN_TYPE"),
                                    "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                                    rs.getInt("ORDINAL_POSITION"))));
            List<SchemaSupport.IndexColumnRow> indexes = jdbc().query(indexesSql,
                    (rs, i) -> new SchemaSupport.IndexColumnRow(
                            rs.getString("table_name"), rs.getString("index_name"),
                            rs.getString("column_name"), rs.getBoolean("is_unique")));
            return SchemaSupport.build(instance.getType().name(), instance.getDbName(),
                    columns, indexes, SchemaSupport.DEFAULT_MAX_TABLES);
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 스키마 조회 실패: " + e.getMessage(), e);
        }
    }

    /** 파라미터 — sys.configurations(name/value_in_use). 실제 적용값 기준. 단위 없어 unit=null */
    @Override
    public List<DbParameter> parameters() {
        try {
            return jdbc().query(
                    "SELECT name, CONVERT(varchar, value_in_use) AS value FROM sys.configurations ORDER BY name",
                    (rs, i) -> ParameterSupport.of(rs.getString("name"), rs.getString("value"), null));
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 파라미터 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public String explain(String sql) {
        requireSelect(sql);
        // 쿼리를 직접 실행하지 않고 플랜 캐시에서 유사 텍스트의 실제 사용 플랜(XML)을 찾는다
        String planSql = """
                SELECT TOP 1 qp.query_plan
                FROM sys.dm_exec_query_stats qs
                CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                CROSS APPLY sys.dm_exec_query_plan(qs.plan_handle) qp
                WHERE st.text LIKE ?
                ORDER BY qs.last_execution_time DESC
                """;
        String prefix = sql.length() > 100 ? sql.substring(0, 100) : sql;
        try {
            return jdbc().query(planSql, rs -> {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return "플랜 캐시에서 해당 쿼리를 찾지 못했습니다. 쿼리가 한 번 이상 실행된 뒤 다시 시도하세요.";
            }, "%" + prefix + "%");
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 실행계획 조회 실패: " + e.getMessage(), e);
        }
    }
}
