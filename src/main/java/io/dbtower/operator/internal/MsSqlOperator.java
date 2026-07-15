package io.dbtower.operator.internal;

import io.dbtower.operator.model.BackupPolicy;
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
import io.dbtower.operator.model.RestoreVerification;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.SessionInfo;
import io.dbtower.operator.model.SlowQuery;
import io.dbtower.operator.model.TableDetail;
import io.dbtower.operator.model.TableStat;
import io.dbtower.operator.model.WaitEvent;

import io.dbtower.registry.DatabaseInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import org.springframework.jdbc.core.ConnectionCallback;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * SQL Server 어댑터.
 *
 * 통계 소스: sys.dm_exec_query_stats DMV (플랜 캐시 기반 누적 통계)
 * 실행계획: 임의 쿼리 실행 대신 플랜 캐시(sys.dm_exec_query_plan)에서 조회한다 —
 * 운영 DB에 부하를 주지 않고 실제 사용된 플랜을 보는 방식.
 */
public class MsSqlOperator extends AbstractJdbcOperator {

    private static final Logger log = LoggerFactory.getLogger(MsSqlOperator.class);

    /**
     * SERVERPROPERTY('EngineEdition') 값의 의미(A-5):
     * 5 = Azure SQL Database(관리형 단일 DB) — system_health 세션·로컬 .xel 파일이 없어 데드락 조회 불가,
     * 8 = Azure SQL Managed Instance — system_health가 있어 온프렘과 동일 경로,
     * 그 외(1=Personal/Desktop, 2=Standard, 3=Enterprise, 4=Express 등) = 온프렘/일반 인스턴스(기존 경로).
     */
    static final int ENGINE_EDITION_AZURE_SQL_DB = 5;

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
        // useTls면 encrypt=true + 인증서 체인 검증(trustServerCertificate=false) — Azure SQL 등
        // TLS 강제 환경 대응. 검증을 끄는 우회는 일부러 안 둔다(자가서명이면 truststore에 등록).
        String encrypt = instance.isUseTls() ? "encrypt=true;trustServerCertificate=false" : "encrypt=false";
        return "jdbc:sqlserver://%s:%d;databaseName=%s;%s;loginTimeout=3"
                .formatted(instance.getHost(), instance.getPort(), instance.getDbName(), encrypt);
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
     * 인덱스 사용 통계 (D6) — sys.dm_db_index_usage_stats의 user_seeks+user_scans+user_lookups 합이
     * 인덱스별 사용 누적이다. 이 DMV는 사용된 적 있는 인덱스만 행을 가지므로 sys.indexes에 LEFT JOIN해
     * 한 번도 안 쓰인 인덱스는 scan_count=0으로 채운다(미사용 후보). index_id>0으로 힙(0)은 제외하고,
     * is_unique로 유니크/PK 인덱스를 구분한다. <b>한계:</b> 이 DMV는 서버 재기동 시 초기화되므로 0회가
     * 미사용인지 판정하려면 가동 기간을 함께 봐야 한다. 읽기 전용.
     */
    @Override
    public List<IndexUsage> indexUsage(int limit) {
        String sql = """
                SELECT TOP (?)
                       OBJECT_NAME(i.object_id) AS table_name,
                       i.name AS index_name,
                       ISNULL(u.user_seeks, 0) + ISNULL(u.user_scans, 0) + ISNULL(u.user_lookups, 0) AS scan_count,
                       i.is_unique
                FROM sys.indexes i
                JOIN sys.tables t ON t.object_id = i.object_id
                LEFT JOIN sys.dm_db_index_usage_stats u
                       ON u.object_id = i.object_id AND u.index_id = i.index_id AND u.database_id = DB_ID()
                WHERE i.index_id > 0 AND i.name IS NOT NULL
                ORDER BY scan_count ASC
                """;
        try {
            return jdbc().query(sql,
                    (rs, i) -> new IndexUsage(
                            rs.getString("table_name"),
                            rs.getString("index_name"),
                            rs.getObject("scan_count", Long.class),
                            // 인덱스별 크기는 미사용 판정에 불필요해 담지 않는다(테이블 크기는 tableStats)
                            null,
                            rs.getBoolean("is_unique"),
                            IndexUsage.NATIVE),
                    limit);
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 인덱스 사용 통계 조회 실패: " + e.getMessage(), e);
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

    /**
     * 테이블 상세 (심화 아크 3) — 기본 통계 + 인덱스 + 재구성 DDL을 한 번에. "값과 출처의 정직"이 원칙이다.
     *
     * <p>engine=null: SQL Server에는 MySQL의 InnoDB 같은 스토리지 엔진 개념이 없어 정직하게 null이다.
     *
     * <p>cardinality=null: 인덱스별 고유값은 DBCC SHOW_STATISTICS로만 얻는데 무겁고 기본 노출이 아니라
     * (권한·부하) 여기서 뽑지 않는다 — 지어내지 않고 미확보(null)로 둔다.
     *
     * <p>DDL은 RECONSTRUCTED: SQL Server에는 SHOW CREATE TABLE이 없어 카탈로그(INFORMATION_SCHEMA)에서
     * 근사 CREATE TABLE을 재구성한다. 제약조건(FK/CHECK)·트리거·계산열 등은 담지 않는 근사다.
     *
     * <p>테이블명은 sys 뷰/INFORMATION_SCHEMA에 문자열 파라미터로 바인딩한다(주입 방어). 읽기 전용.
     */
    @Override
    public TableDetail tableDetail(String tableName) {
        String table = TableDetailSupport.requireIdentifier(tableName);
        // 기본 통계: tableStats와 동일한 집계(used_page_count는 8KB 페이지 → 바이트 환산), create_date는 생성 시각.
        String statsSql = """
                SELECT CONVERT(varchar(30), t.create_date, 126) AS created_at,
                       SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.row_count ELSE 0 END) AS row_count,
                       SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.used_page_count ELSE 0 END) * 8192 AS data_bytes,
                       SUM(CASE WHEN ps.index_id > 1 THEN ps.used_page_count ELSE 0 END) * 8192 AS index_bytes
                FROM sys.tables t
                JOIN sys.dm_db_partition_stats ps ON ps.object_id = t.object_id
                WHERE t.name = ?
                GROUP BY t.create_date
                """;
        // 인덱스: 힙(index_id=0, name NULL)은 제외하고 키 컬럼만(INCLUDE 컬럼은 인덱스 키가 아니라 제외). type_desc=CLUSTERED 등.
        String indexSql = """
                SELECT i.name AS index_name, i.type_desc AS index_type, i.is_unique, c.name AS column_name
                FROM sys.indexes i
                JOIN sys.tables t ON t.object_id = i.object_id
                JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                WHERE t.name = ? AND i.index_id > 0 AND i.name IS NOT NULL AND ic.is_included_column = 0
                ORDER BY i.name, ic.key_ordinal
                """;
        // DDL 재구성용 컬럼 — describeSchema와 같은 방식으로 DATA_TYPE에 길이/정밀도를 붙여 기종 표기에 가깝게.
        String columnsSql = """
                SELECT COLUMN_NAME,
                       DATA_TYPE + COALESCE('(' + CASE
                           WHEN CHARACTER_MAXIMUM_LENGTH = -1 THEN 'max'
                           WHEN CHARACTER_MAXIMUM_LENGTH IS NOT NULL
                               THEN CONVERT(varchar, CHARACTER_MAXIMUM_LENGTH)
                           WHEN DATA_TYPE IN ('decimal','numeric')
                               THEN CONVERT(varchar, NUMERIC_PRECISION) + ',' + CONVERT(varchar, NUMERIC_SCALE)
                           ELSE NULL END + ')', '') AS COLUMN_TYPE,
                       IS_NULLABLE, COLUMN_DEFAULT
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """;
        // PK 컬럼 — TABLE_CONSTRAINTS(PRIMARY KEY)에 KEY_COLUMN_USAGE를 붙여 키 순서대로.
        String pkSql = """
                SELECT kcu.COLUMN_NAME
                FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                       ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME AND kcu.TABLE_NAME = tc.TABLE_NAME
                WHERE tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'PRIMARY KEY'
                ORDER BY kcu.ORDINAL_POSITION
                """;
        try {
            BasicStats stats = jdbc().query(statsSql, rs -> {
                if (!rs.next()) {
                    return null; // JOIN 결과가 없으면 그 이름의 테이블이 없다는 뜻
                }
                return new BasicStats(
                        rs.getString("created_at"),
                        rs.getLong("row_count"),
                        rs.getLong("data_bytes"),
                        rs.getLong("index_bytes"));
            }, table);
            if (stats == null) {
                return TableDetail.unsupported(table, "테이블을 찾을 수 없습니다: " + table);
            }
            // avgRowBytes = data_bytes / row_count. 0행이면 0으로 나눌 수 없어 -1(미확보).
            long avgRowBytes = stats.rowCount() > 0 ? stats.dataBytes() / stats.rowCount() : -1;

            // 인덱스 행(컬럼 단위)을 인덱스명으로 접어 컬럼 순서를 보존한다. cardinality는 위 사유로 전부 null.
            List<IndexRow> rows = jdbc().query(indexSql, (rs, i) -> new IndexRow(
                    rs.getString("index_name"), rs.getString("index_type"),
                    rs.getBoolean("is_unique"), rs.getString("column_name")), table);
            LinkedHashMap<String, List<String>> indexColumns = new LinkedHashMap<>();
            LinkedHashMap<String, IndexRow> indexMeta = new LinkedHashMap<>();
            for (IndexRow r : rows) {
                indexColumns.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r.column());
                indexMeta.putIfAbsent(r.name(), r);
            }
            List<TableDetail.IndexDetail> indexes = new ArrayList<>();
            for (var e : indexColumns.entrySet()) {
                IndexRow m = indexMeta.get(e.getKey());
                indexes.add(new TableDetail.IndexDetail(
                        e.getKey(), e.getValue(), m.unique(), m.type(), null)); // cardinality=null (미확보 정직)
            }

            List<TableDetailSupport.ColumnDef> columns = jdbc().query(columnsSql, (rs, i) ->
                    new TableDetailSupport.ColumnDef(
                            rs.getString("COLUMN_NAME"),
                            rs.getString("COLUMN_TYPE"),
                            "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                            rs.getString("COLUMN_DEFAULT")), table);
            List<String> pkColumns = jdbc().queryForList(pkSql, String.class, table);
            // 인덱스 정의는 참고용 주석 라인으로만 덧붙인다(재구성 CREATE TABLE 본문 밖).
            List<String> indexDefs = new ArrayList<>();
            for (TableDetail.IndexDetail idx : indexes) {
                indexDefs.add("-- index: " + idx.name() + " (" + String.join(", ", idx.columns()) + ")"
                        + (idx.unique() ? " UNIQUE" : "") + " " + idx.type());
            }
            String ddl = TableDetailSupport.reconstructDdl(table, columns, pkColumns, indexDefs);

            String note = "SQL Server는 스토리지 엔진 개념이 없어 engine=null. "
                    + "카디널리티는 SQL Server 기본 노출이 아니라 미확보(DBCC SHOW_STATISTICS는 무거워 조회 안 함). "
                    + "DDL은 SHOW CREATE TABLE이 없어 카탈로그에서 재구성한 근사이며 제약조건(FK/CHECK)·트리거는 생략됨.";
            return new TableDetail(table, null, stats.rowCount(), stats.dataBytes(), stats.indexBytes(),
                    avgRowBytes, stats.createdAt(), ddl, TableDetail.DdlSource.RECONSTRUCTED, indexes, note);
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 테이블 상세 조회 실패: " + e.getMessage(), e);
        }
    }

    /** tableDetail 기본 통계 결과 한 줄 — 메서드 로컬 값 객체. */
    private record BasicStats(String createdAt, long rowCount, long dataBytes, long indexBytes) {
    }

    /** 인덱스 컬럼 단위 행 — 인덱스명으로 접기 전 원자료. */
    private record IndexRow(String name, String type, boolean unique, String column) {
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

    /**
     * 플랜 변경 감지용 shape (plan flip) — Query Store가 query_id당 plan_id를 <b>이력으로 보존</b>하므로
     * (플랜 캐시와 달리 축출로 사라지지 않음) 정규화 텍스트 없이도 NATIVE로 플립을 잡는다. queryId =
     * query_hash 16진 문자열.
     *
     * "있으면 쓴다" 게이트: Query Store가 꺼진 DB(2019 기본 OFF·복원/업그레이드 DB)는 empty로 스킵 —
     * 켜는 행위(ALTER DATABASE)는 대상 변경이라 하지 않는다(2022 신규 DB는 기본 ON). is_forced_plan이면
     * shape에 [FORCED] 표기(누군가 플랜을 강제한 상태 관측 — 강제 실행 자체는 안 함). 권한 VIEW DATABASE STATE.
     */
    @Override
    public Optional<String> planShapeForDigest(String queryId, String queryText) {
        try {
            // 게이트: Query Store 활성 상태 확인 (OFF면 스킵)
            String state = jdbc().query(
                    "SELECT actual_state_desc FROM sys.database_query_store_options",
                    rs -> rs.next() ? rs.getString(1) : null);
            if (state == null || !(state.equals("READ_WRITE") || state.equals("READ_ONLY"))) {
                return Optional.empty();
            }
            String planXml = jdbc().query("""
                    SELECT TOP 1 CAST(p.query_plan AS nvarchar(max)) AS plan_xml, p.is_forced_plan
                    FROM sys.query_store_query q
                    JOIN sys.query_store_plan p ON p.query_id = q.query_id
                    WHERE q.query_hash = CONVERT(binary(8), ?, 1)
                    ORDER BY p.last_execution_time DESC
                    """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                String xml = rs.getString("plan_xml");
                return rs.getBoolean("is_forced_plan") ? "[FORCED]" + xml : xml;
            }, queryId);
            if (planXml == null) {
                return Optional.empty();
            }
            boolean forced = planXml.startsWith("[FORCED]");
            String xml = forced ? planXml.substring("[FORCED]".length()) : planXml;
            String shape = PlanShapes.fromMssqlXml(xml);
            return Optional.of(forced ? "[FORCED]" + shape : shape);
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    /** p95의 표준정규 z값 — 정규분포에서 상위 5% 경계. */
    static final double Z95 = 1.645;

    /** p99의 표준정규 z값 — 정규분포에서 상위 1% 경계. */
    static final double Z99 = 2.326;

    /** 최근 윈도우 길이(시간). Query Store interval 중 이 구간에 시작한 것만 재집계해 "최근 부하"를 본다. */
    static final int RECENT_HOURS = 2;

    /**
     * 레이턴시 백분위 (B-2, ESTIMATED) — Query Store가 켜져 있으면 avg_duration + z×stdev_duration으로 근사한다.
     *
     * <p>왜 ESTIMATED인가: Query Store는 분위수 원자료를 주지 않고 구간별 평균·최대·표준편차만 준다. 그래서
     * 정규분포를 가정해 mean + z×stdev로 근사한다(p95 z=1.645, p99 z=2.326). 실제 레이턴시 분포는 오른쪽
     * 꼬리가 무거워(락 대기·IO 스파이크) 이 근사는 대개 <b>과소평가</b>한다 — 진짜 백분위가 아니라 참고용 하한이다.
     *
     * <p>왜 max로 캡을 씌우나: 표준편차가 큰 쿼리는 avg + z×stdev가 관측된 max_duration을 넘어설 수 있는데,
     * 추정치가 "실제로 본 최대"보다 크면 명백히 틀린 값이라 max로 상한을 건다(min(추정, max)).
     *
     * <p>왜 dm_exec_query_stats 누적이 아니라 구간 interval인가: dm_exec_query_stats는 플랜 캐시 생존 기간
     * 전체의 누적 평균이라 "지금 느려졌는지"를 못 본다. Query Store의 runtime_stats는 시간 구간(interval)별로
     * 쪼개 보존하므로 최근 N시간만 골라 최근 부하의 분포를 근사할 수 있다.
     *
     * <p>함정: 활성 interval은 같은 plan을 in-memory 버퍼와 flush된 행으로 <b>중복</b> 집계하고 execution_type
     * (정상/중단/예외)별로도 행이 갈린다. 그래서 avg_duration을 단순 AVG하면 안 되고 count_executions로 가중
     * 재집계(Σ(avg×cnt)/Σcnt)해야 한다 — 아래 쿼리가 그렇게 SUM으로 재집계한다.
     *
     * <p>게이트: Query Store가 꺼진 DB면 원자료(구간 stdev)가 없어 근사조차 불가능하므로 UNSUPPORTED 안내 행
     * 하나만 돌려준다. 켜는 행위(ALTER DATABASE)는 대상 DB를 바꾸는 것이라 <b>하지 않는다</b>(읽기 전용). 단위는
     * 마이크로초(µs)라 ms로 환산(/1000)하고, source=ESTIMATED로 실측과 반드시 구분한다.
     */
    @Override
    public List<LatencyPercentile> latencyPercentiles(int limit) {
        try {
            // 게이트: Query Store 활성 상태 확인 (OFF면 근사 재료가 없어 UNSUPPORTED). planShapeForDigest와 동일 패턴.
            String state = jdbc().query(
                    "SELECT actual_state_desc FROM sys.database_query_store_options",
                    rs -> rs.next() ? rs.getString(1) : null);
            if (state == null || !(state.equals("READ_WRITE") || state.equals("READ_ONLY"))) {
                return List.of(LatencyPercentile.unsupported(
                        "SQL Server 레이턴시 백분위: Query Store가 이 DB에서 꺼져 있어 원자료(구간 stdev) 없음 — "
                        + "켜면 ESTIMATED 제공. Query Store를 켜는 행위(ALTER DATABASE)는 대상 DB 변경이라 하지 않는다."));
            }
            // TOP(?) = limit, DATEADD(hour, -?, ...) = RECENT_HOURS 순서로 바인딩한다
            String sql = """
                    SELECT TOP (?)
                           CONVERT(varchar(64), q.query_hash, 1) AS query_id,
                           SUBSTRING(MAX(qt.query_sql_text), 1, 200) AS query_text,
                           SUM(rs.avg_duration * rs.count_executions) / NULLIF(SUM(rs.count_executions), 0) AS avg_us,
                           MAX(rs.max_duration) AS max_us,
                           MAX(rs.stdev_duration) AS stdev_us
                    FROM sys.query_store_runtime_stats rs
                    JOIN sys.query_store_runtime_stats_interval i
                           ON i.runtime_stats_interval_id = rs.runtime_stats_interval_id
                    JOIN sys.query_store_plan p ON p.plan_id = rs.plan_id
                    JOIN sys.query_store_query q ON q.query_id = p.query_id
                    JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
                    WHERE i.start_time >= DATEADD(hour, -?, SYSUTCDATETIME())
                    GROUP BY q.query_hash
                    ORDER BY SUM(rs.avg_duration * rs.count_executions) DESC
                    """;
            return jdbc().query(sql,
                    (rs, i) -> {
                        double avgMs = rs.getDouble("avg_us") / 1000.0;
                        double maxMs = rs.getDouble("max_us") / 1000.0;
                        // stdev_duration이 NULL(단일 실행 등)이면 getDouble이 0을 주므로 p95=avg(캡 적용)로 자연 처리된다
                        double stdevMs = rs.getDouble("stdev_us") / 1000.0;
                        return new LatencyPercentile(
                                rs.getString("query_id"),
                                rs.getString("query_text"),
                                estimateCapped(avgMs, stdevMs, maxMs, Z95),
                                estimateCapped(avgMs, stdevMs, maxMs, Z99),
                                LatencyPercentile.ESTIMATED);
                    },
                    limit, RECENT_HOURS);
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 레이턴시 백분위 근사 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 정규분포 근사 백분위 = min(avg + z×stdev, max). 추정이 관측 최대를 넘지 않게 max로 캡을 씌우고
     * 소수 둘째 자리로 반올림한다. stdev=0이면 avg가 그대로(캡 적용). 단위는 ms.
     */
    static Double estimateCapped(double avgMs, double stdevMs, double maxMs, double z) {
        double est = avgMs + z * stdevMs;
        double capped = Math.min(est, maxMs);
        return Math.round(capped * 100.0) / 100.0;
    }

    /**
     * 실제 실행 계획 (D9) — SET STATISTICS XML ON 후 쿼리를 실행하면, 실행 계획이 쿼리 결과와는
     * <b>별도 결과셋</b>(XML)으로 따라온다. 그래서 PreparedStatement가 아니라 plain Statement로 실행하고
     * getMoreResults()로 결과셋을 순회해 ShowPlanXML을 찾는다(PreparedStatement는 이 부가 결과셋을 놓치는 함정).
     * XML 안에 EstimateRows(추정) vs RunTimeInformation ActualRows(실측), missing index·경고(CONVERT_IMPLICIT 등)가 담긴다.
     *
     * 안전: setQueryTimeout으로 실행을 상한하고, 마지막에 SET STATISTICS XML OFF로 세션 상태를 되돌린다. SELECT 전용.
     */
    @Override
    public String explainAnalyze(String sql) {
        requireSelect(sql);
        try {
            return jdbc().execute((ConnectionCallback<String>) conn -> {
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout((int) (DEEP_DIAGNOSIS_TIMEOUT_MS / 1000));
                    st.execute("SET STATISTICS XML ON");
                    StringBuilder xml = new StringBuilder();
                    try {
                        boolean hasResult = st.execute(sql);
                        while (true) {
                            if (hasResult) {
                                try (ResultSet rs = st.getResultSet()) {
                                    while (rs.next()) {
                                        String val = rs.getString(1);
                                        // 실행 계획 결과셋은 ShowPlanXML을 담은 단일 컬럼으로 온다
                                        if (val != null && val.contains("ShowPlanXML")) {
                                            xml.append(val);
                                        }
                                    }
                                }
                            } else if (st.getUpdateCount() == -1) {
                                break; // 결과셋도 갱신카운트도 없으면 끝
                            }
                            hasResult = st.getMoreResults();
                        }
                    } finally {
                        st.execute("SET STATISTICS XML OFF");
                    }
                    return xml.length() > 0 ? xml.toString()
                            : "실제 실행 계획(ShowPlanXML)을 결과셋에서 찾지 못했습니다";
                }
            });
        } catch (DataAccessException e) {
            throw new OperatorException("MSSQL 실제 실행계획(STATISTICS XML) 실패(타임아웃/권한 확인): "
                    + e.getMessage(), e);
        }
    }

    /** inputbuf 문장의 최대 보존 길이(자) — QueryStat/SUBSTRING 관례처럼 과도한 SQL 텍스트를 자른다. */
    static final int DEADLOCK_STMT_MAX = 200;

    /** victim 요약에 붙이는 inputbuf 앞부분 길이(자) — 어느 세션이 롤백됐는지 식별할 정도만. */
    static final int DEADLOCK_VICTIM_MAX = 120;

    /** 데드락 리포트 획득 방식 라벨 — DeadlockEvent.source 규약값. */
    static final String DEADLOCK_SOURCE = "MSSQL system_health XE";

    /**
     * 최근 데드락 (3차 아크 D-1) — system_health 확장 이벤트 세션이 데드락마다 남기는
     * xml_deadlock_report를 <b>설정 변경 0으로</b> 읽는다(system_health는 SQL Server가 기본으로 켜 둠).
     *
     * <p>두 타깃을 모두 읽어 병합하는 이유(라이브 실측 근거): 조사 단계에선 ring_buffer가 2022에서
     * xml_deadlock_report를 빈 결과로 주는 사례가 보고됐으나, 실제 데모(SQL Server 2022 Linux)에선 반대로
     * <b>방금 발생한 데드락이 ring_buffer에만 즉시 나타나고 .xel 파일 타깃에는 아직 flush되지 않는</b>
     * 현상을 확인했다. 그래서 파일 타깃(과거 롤오버 포함)과 ring_buffer(가장 최근)를 <b>둘 다</b> 읽고
     * 내용으로 중복 제거해 어느 쪽 한계에도 최근 데드락을 놓치지 않는다.
     *
     * <p>정직한 한계: 둘 다 크기·개수 상한이 있는 <b>롤링</b> 저장이라 "최근"만 남고 오래된 데드락은 관측
     * 범위 밖이다(과거 전수 이력을 보장하지 않는다).
     *
     * <p>권한: sys.fn_xe_file_target_read_file·dm_xe_session_targets 조회에는 VIEW SERVER STATE가 필요하다 —
     * 없으면 이 메서드는 OperatorException으로 실패하고 상위에서 ERROR로 격리된다.
     */
    @Override
    public List<DeadlockEvent> recentDeadlocks(int limit) {
        // A-5: Azure SQL Database(EngineEdition=5)는 system_health XE 세션도 로컬 .xel 파일도 제공하지 않는다.
        // 그대로 조회하면 fn_xe_file_target_read_file/dm_xe_sessions가 빈 결과를 주어 "데드락 없음"으로 오탐한다.
        // 그래서 여기서 명시적으로 "이 에디션은 system_health 미제공"을 로그로 남기고 빈 목록을 정직하게 돌려준다.
        // (온프렘·Managed Instance(8)는 system_health가 있어 아래 기존 경로로 진행한다.)
        try {
            Integer engineEdition = jdbc().queryForObject(
                    "SELECT CONVERT(int, SERVERPROPERTY('EngineEdition'))", Integer.class);
            if (engineEdition != null && engineEdition == ENGINE_EDITION_AZURE_SQL_DB) {
                log.info("MSSQL 데드락 조회 스킵 — Azure SQL Database(EngineEdition=5)는 system_health XE 세션/"
                        + ".xel 파일을 제공하지 않아 데드락 이력을 관측할 수 없습니다 (instance={}). 빈 목록 반환.",
                        instance.getName());
                return List.of();
            }
        } catch (DataAccessException e) {
            throw new OperatorException(
                    "MSSQL EngineEdition 확인 실패(VIEW SERVER STATE 권한/접속 확인): " + e.getMessage(), e);
        }
        // 파일 타깃: 여러 .xel 롤오버 파일을 한 번에 훑고 file_name/file_offset 역순으로 최근을 먼저.
        String fileSql = """
                SELECT TOP (?) CAST(event_data AS XML) AS deadlock_xml
                FROM sys.fn_xe_file_target_read_file('system_health*.xel', NULL, NULL, NULL)
                WHERE object_name = 'xml_deadlock_report'
                ORDER BY file_name DESC, file_offset DESC
                """;
        // ring_buffer 타깃: 인메모리라 방금 발생한 최근 데드락을 즉시 담는다(파일 flush 지연 보완).
        String ringSql = """
                SELECT CAST(t.target_data AS NVARCHAR(MAX)) AS ring_xml
                FROM sys.dm_xe_session_targets t
                JOIN sys.dm_xe_sessions s ON s.address = t.event_session_address
                WHERE s.name = 'system_health' AND t.target_name = 'ring_buffer'
                """;
        try {
            List<DeadlockEvent> merged = new ArrayList<>();
            // SQLXML 드라이버 편차를 피하려고 XML을 문자열로 받아(getString) 우리가 DOM으로 파싱한다.
            for (String xml : jdbc().query(fileSql, (rs, i) -> rs.getString("deadlock_xml"), limit)) {
                DeadlockEvent ev = parseDeadlockXml(xml); // 개별 파싱 실패는 null → 스킵
                if (ev != null) {
                    merged.add(ev);
                }
            }
            for (String ringXml : jdbc().queryForList(ringSql, String.class)) {
                merged.addAll(parseRingBufferDeadlocks(ringXml));
            }
            // 내용(시각+victim+리소스)으로 중복 제거 — 파일·ring에 같은 사건이 겹쳐 잡힐 수 있다.
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<DeadlockEvent> deduped = new ArrayList<>();
            for (DeadlockEvent ev : merged) {
                String key = ev.detectedAt() + "|" + ev.victim() + "|" + ev.resource();
                if (seen.add(key)) {
                    deduped.add(ev);
                }
            }
            // 최근순 정렬(detectedAt 문자열은 ISO/정렬 가능 형식) 후 상한.
            deduped.sort((a, b) -> {
                String x = a.detectedAt() == null ? "" : a.detectedAt();
                String y = b.detectedAt() == null ? "" : b.detectedAt();
                return y.compareTo(x);
            });
            return deduped.size() > limit ? deduped.subList(0, limit) : deduped;
        } catch (DataAccessException e) {
            throw new OperatorException(
                    "MSSQL 데드락 조회 실패(VIEW SERVER STATE 권한/타깃 확인): " + e.getMessage(), e);
        }
    }

    /**
     * xml_deadlock_report 한 건(event_data XML)을 DeadlockEvent로 변환한다. JDBC 없이 테스트 가능하도록
     * 파싱만 담당하는 순수 메서드다. 깨진/부분 XML은 예외를 던지지 않고 {@code null}을 돌려준다(호출부가 스킵).
     *
     * <p>구조: {@code <deadlock>} 루트 안에 victim-list(롤백된 프로세스 id) / process-list(각 프로세스의
     * inputbuf=그 세션 SQL) / resource-list(경합 락 리소스). 발생 시각은 {@code <deadlock>} 또는 이를 감싼
     * {@code <event>}의 @timestamp를 방어적으로 둘 다 시도한다(파일 타깃은 event로 감싸 준다).
     */
    static DeadlockEvent parseDeadlockXml(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // deadlock 리포트는 네임스페이스가 없어 tag 이름으로 바로 찾는다(showplan과 달리 setNamespaceAware 불필요).
            // XXE 방어(A-1) — DeepAnalyzer와 동일하게 보안 처리 강제 + DOCTYPE 자체를 거부해 외부 엔티티 fetch를 원천 차단한다.
            f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Element deadlock = first(doc, "deadlock");
            if (deadlock == null) {
                return null; // deadlock 요소가 없으면 우리가 다룰 리포트가 아니다
            }
            // 발생 시각: deadlock/@timestamp 우선, 없으면 부모 event/@timestamp(없으면 null)
            String detectedAt = attrOrNull(deadlock, "timestamp");
            if (detectedAt == null) {
                Element event = first(doc, "event");
                if (event != null) {
                    detectedAt = attrOrNull(event, "timestamp");
                }
            }
            return parseDeadlockElement(deadlock, detectedAt);
        } catch (Exception e) {
            return null; // 개별 리포트 파싱 실패는 스킵(전체 조회를 죽이지 않는다)
        }
    }

    /**
     * deadlock 요소 하나를 DeadlockEvent로 — file target과 ring_buffer가 공유하는 코어 파서.
     * victimProcess는 이 deadlock 하위로 스코프해(ring_buffer의 다중 deadlock 대비) 다른 사건과 섞이지 않게 한다.
     */
    private static DeadlockEvent parseDeadlockElement(Element deadlock, String detectedAt) {
        Element victimProcess = first(deadlock, "victimProcess");
        String victimId = victimProcess != null ? attrOrNull(victimProcess, "id") : null;

        List<String> statements = new ArrayList<>();
        String victimSummary = null;
        NodeList processes = deadlock.getElementsByTagName("process");
        for (int i = 0; i < processes.getLength(); i++) {
            Element p = (Element) processes.item(i);
            String inputbuf = collapse(textOfChild(p, "inputbuf"));
            statements.add(clip(inputbuf, DEADLOCK_STMT_MAX)); // inputbuf 없으면 빈 문자열
            if (victimId != null && victimId.equals(p.getAttribute("id"))) {
                String spid = p.getAttribute("spid");
                String label = spid.isBlank() ? victimId : spid;
                victimSummary = "spid " + label + " / " + clip(inputbuf, DEADLOCK_VICTIM_MAX);
            }
        }
        return new DeadlockEvent(detectedAt, statements, victimSummary,
                summarizeResources(deadlock), DEADLOCK_SOURCE);
    }

    /**
     * ring_buffer 타깃의 XML(여러 xml_deadlock_report event 포함)에서 데드락들을 파싱한다.
     * 파일 타깃이 아직 flush하지 않은 <b>가장 최근</b> 데드락은 ring_buffer에만 있을 수 있어 함께 읽는다
     * (라이브 실측에서 SQL Server 2022 Linux는 방금 발생한 데드락이 ring_buffer에만 즉시 나타났다).
     */
    static List<DeadlockEvent> parseRingBufferDeadlocks(String ringXml) {
        List<DeadlockEvent> out = new ArrayList<>();
        if (ringXml == null || ringXml.isBlank()) {
            return out;
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // XXE 방어(A-1) — parseDeadlockXml과 동일한 강도(보안 처리 + DOCTYPE 거부)로 맞춘다.
            f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(ringXml.getBytes(StandardCharsets.UTF_8)));
            NodeList events = doc.getElementsByTagName("event");
            for (int i = 0; i < events.getLength(); i++) {
                Element event = (Element) events.item(i);
                if (!"xml_deadlock_report".equals(event.getAttribute("name"))) {
                    continue;
                }
                Element deadlock = first(event, "deadlock");
                if (deadlock == null) {
                    continue;
                }
                String detectedAt = attrOrNull(deadlock, "timestamp");
                if (detectedAt == null) {
                    detectedAt = attrOrNull(event, "timestamp");
                }
                DeadlockEvent ev = parseDeadlockElement(deadlock, detectedAt);
                if (ev != null) {
                    out.add(ev);
                }
            }
        } catch (Exception e) {
            return out; // 파싱 실패는 파일 타깃 결과만으로 진행
        }
        return out;
    }

    /** resource-list의 락 리소스를 objectname[.indexname] 요약으로 — 중복 제거 후 세미콜론 결합(없으면 null). */
    private static String summarizeResources(Element deadlock) {
        Element resourceList = first(deadlock, "resource-list");
        if (resourceList == null) {
            return null;
        }
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        NodeList all = resourceList.getElementsByTagName("*"); // keylock/pagelock/objectlock 등 종류 무관
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            String obj = el.getAttribute("objectname");
            if (obj.isBlank()) {
                continue;
            }
            String idx = el.getAttribute("indexname");
            parts.add(idx.isBlank() ? obj : obj + "." + idx);
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /** 문서 전체에서 태그명이 tag인 첫 요소(없으면 null). */
    private static Element first(Document doc, String tag) {
        NodeList nl = doc.getElementsByTagName(tag);
        return nl.getLength() > 0 ? (Element) nl.item(0) : null;
    }

    /** parent 하위에서 태그명이 tag인 첫 요소(없으면 null). */
    private static Element first(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() > 0 ? (Element) nl.item(0) : null;
    }

    /** 자식 요소 tag의 텍스트(없으면 빈 문자열). */
    private static String textOfChild(Element parent, String tag) {
        Element child = first(parent, tag);
        if (child == null) {
            return "";
        }
        String text = child.getTextContent();
        return text == null ? "" : text;
    }

    /** 속성이 비어있으면 null(있으면 그대로). */
    private static String attrOrNull(Element el, String name) {
        String v = el.getAttribute(name);
        return v.isBlank() ? null : v;
    }

    /** 공백(개행·연속 스페이스)을 하나로 정리하고 앞뒤를 다듬는다. */
    private static String collapse(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** max자를 넘으면 잘라내고 말줄임(...)을 붙인다. */
    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
