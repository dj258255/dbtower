package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
        List<QueryStat> stats = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.add(new QueryStat(
                            rs.getString("sql_id"),
                            rs.getString("query_text"),
                            rs.getLong("calls"),
                            rs.getDouble("total_ms"),
                            rs.getLong("logical_reads")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("Oracle 쿼리 통계 수집 실패: " + e.getMessage(), e);
        }
        return stats;
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
        List<SlowQuery> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SlowQuery(
                            rs.getString("query_text"),
                            rs.getDouble("avg_ms"),
                            rs.getLong("avg_reads"),
                            rs.getString("captured_at")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("Oracle 슬로우 쿼리 조회 실패: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public String explain(String sql) {
        requireSelect(sql);
        // EXPLAIN PLAN은 세션별 PLAN_TABLE(글로벌 임시 테이블)에 쓰므로 같은 커넥션에서 이어 읽는다
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute("EXPLAIN PLAN FOR " + sql);
            StringBuilder plan = new StringBuilder();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            }
            return plan.toString();
        } catch (SQLException e) {
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
        List<TableStat> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TableStat(
                            rs.getString("table_name"),
                            rs.getLong("row_count"),
                            rs.getLong("data_bytes"),
                            rs.getLong("index_bytes")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("Oracle 테이블 통계 조회 실패: " + e.getMessage(), e);
        }
        return result;
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
        List<WaitEvent> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new WaitEvent(
                            rs.getString("event"),
                            rs.getString("wait_class"),
                            rs.getLong("total_waits"),
                            rs.getDouble("total_ms")));
                }
            }
        } catch (SQLException e) {
            throw new OperatorException("Oracle 대기 이벤트 조회 실패: " + e.getMessage(), e);
        }
        return result;
    }

    /** 복제 상태 — Data Guard 기준의 데이터베이스 역할. 미구성 단독 인스턴스도 PRIMARY로 표시된다 */
    @Override
    public ReplicationState replicationState() {
        String sql = "SELECT database_role, open_mode, protection_mode FROM v$database";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ReplicationState(
                        rs.getString("database_role"),
                        -1,
                        "open_mode=%s protection=%s".formatted(
                                rs.getString("open_mode"), rs.getString("protection_mode")));
            }
            return new ReplicationState("UNKNOWN", -1, "v$database 조회 결과 없음");
        } catch (SQLException e) {
            throw new OperatorException("Oracle 복제 상태 조회 실패: " + e.getMessage(), e);
        }
    }
}
