package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.HealthStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * JDBC 기반 Operator 공통 골격.
 *
 * 처음엔 호출마다 DriverManager로 새 커넥션을 열었다(TCP+인증 핸드셰이크 반복).
 * 개선 아크 1에서 인스턴스별 HikariCP 풀로 교체 — before/after 실측은 docs/DESIGN.md 참고.
 */
public abstract class AbstractJdbcOperator implements DbmsOperator {

    protected final DatabaseInstance instance;
    protected final BackupTools backupTools;
    private final ConnectionPools pools;

    protected AbstractJdbcOperator(DatabaseInstance instance, ConnectionPools pools, BackupTools backupTools) {
        this.instance = instance;
        this.pools = pools;
        this.backupTools = backupTools;
    }

    /** 기종별 JDBC URL */
    protected abstract String jdbcUrl();

    /** 기종별 버전 조회 쿼리 */
    protected abstract String versionSql();

    protected Connection open() throws SQLException {
        return pools.getConnection(instance, jdbcUrl());
    }

    /**
     * 인스턴스+jdbcUrl에 매인 JdbcTemplate. DataSource는 ConnectionPools가 관리(풀 재사용)하므로
     * 매 호출 새로 만들어도 가볍다 — JdbcTemplate 자체는 상태 없는 얇은 래퍼다.
     */
    protected JdbcTemplate jdbc() {
        return new JdbcTemplate(pools.getDataSource(instance, jdbcUrl()));
    }

    @Override
    public HealthStatus health() {
        long start = System.currentTimeMillis();
        try {
            // 등록 검증(id==null)도 이 경로 — DriverManagerDataSource 1회용 커넥션으로 붙어본다.
            // 접속 실패는 JDBC의 SQLException이 아니라 Spring의 DataAccessException으로 올라온다.
            String version = jdbc().queryForObject(versionSql(), String.class);
            return HealthStatus.up(version != null ? version : "unknown", System.currentTimeMillis() - start);
        } catch (DataAccessException e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    @Override
    public ReplicationState replicationState() {
        throw new UnsupportedOperationException("확장2에서 구현 예정: " + instance.getType());
    }

    /**
     * 기본값은 UNSUPPORTED — 복원 검증 능력을 실제로 갖춘 기종만 오버라이드한다.
     * "자동 검증 못 함"을 "통과"로 위장하지 않기 위해, 미구현은 예외가 아니라 명시적 UNSUPPORTED로.
     */
    @Override
    public RestoreVerification verifyRestore(String location) {
        return RestoreVerification.unsupported(
                instance.getType() + " 자동 복원 검증 미지원 — location=" + location);
    }

    /** 외부 CLI(mysqldump/pg_dump)로 백업을 수행하고 stdout을 파일로 받는 공통 루틴 */
    protected BackupResult runCliBackup(java.util.List<String> command, java.util.Map<String, String> env,
                                        java.nio.file.Path outFile) {
        return BackupCommands.run(command, env, outFile, null);
    }

    protected String backupTimestamp() {
        return BackupCommands.timestamp();
    }

    /**
     * 백업 명령 템플릿의 플레이스홀더를 인스턴스 값으로 치환한다.
     * 템플릿 방식을 쓰는 이유: 같은 mysqldump라도 실행 위치(호스트/컨테이너/원격 에이전트)에 따라
     * 접속 주소와 인자가 달라진다 — 그 환경 차이를 코드가 아니라 설정이 흡수하게 한다.
     * 렌더링/실행의 보안 규칙은 BackupCommands 참고 (MongoDB 추가 때 JDBC 골격에서 분리).
     */
    protected java.util.List<String> renderCommand(String template) {
        return BackupCommands.render(template, instance);
    }

    /** 파일 경로에 들어가는 이름은 안전한 문자만 남긴다 (경로 탈출 방지) */
    protected String safeFileName(String name) {
        return BackupCommands.safeFileName(name);
    }

    /** explain 대상은 SELECT만 허용한다 — 관리 플랫폼이 임의 DML을 실행하면 안 되기 때문. */
    protected void requireSelect(String sql) {
        if (sql == null || !sql.trim().toLowerCase().startsWith("select")) {
            throw new IllegalArgumentException("EXPLAIN은 SELECT 쿼리만 허용합니다");
        }
    }

    /**
     * 기본값은 UNSUPPORTED (B3) — 가상 인덱스 시뮬레이션 능력을 실제로 갖춘 기종만 오버라이드한다.
     * verifyRestore와 같은 정직성 원칙: "실제 인덱스 없이 시뮬레이션 못 함"을 "이득 없음"으로
     * 위장하지 않고 명시적 UNSUPPORTED로 돌려준다.
     */
    @Override
    public IndexAdvice adviseIndex(String sql, String columns) {
        return IndexAdvice.unsupported(instance.getType()
                + " 가상 인덱스 시뮬레이션 미지원 — HypoPG(가상 인덱스)는 PostgreSQL 전용. "
                + "타 기종은 실제 인덱스를 만든 뒤 EXPLAIN을 비교해야 하며 이는 대상 DB를 바꾸는 행위라 범위 밖.");
    }

    /**
     * 기본값은 UNSUPPORTED (D4a) — 레이턴시 백분위 원자료를 실제로 가진 기종만 오버라이드한다.
     * SQL Server(sys.dm_exec_query_stats)·Oracle(V$SQL)의 통계 뷰는 min/max/평균/총계만 제공하고
     * p95/p99 분위수도, 근사에 필요한 표준편차도 주지 않는다. 실측 백분위도 정직한 근사(정규분포 가정)도
     * 낼 수 없으므로, 없는 능력을 있는 척하지 않고 명시적 UNSUPPORTED로 돌려준다(verifyRestore·adviseIndex와 같은 정직성 원칙).
     */
    @Override
    public java.util.List<LatencyPercentile> latencyPercentiles(int limit) {
        return java.util.List.of(LatencyPercentile.unsupported(instance.getType()
                + " 레이턴시 백분위 미지원 — 이 기종의 통계 뷰는 min/max/평균/총계만 제공하고 "
                + "p95/p99 분위수 원자료도 근사에 필요한 표준편차도 없어, 실측 백분위도 정직한 근사도 낼 수 없다."));
    }
}
