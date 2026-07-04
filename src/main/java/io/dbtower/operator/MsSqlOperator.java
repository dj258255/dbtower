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
