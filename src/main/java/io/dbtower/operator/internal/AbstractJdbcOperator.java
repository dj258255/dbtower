package io.dbtower.operator.internal;

import io.dbtower.operator.OperatorException;
import io.dbtower.operator.model.BulkBatchOutcome;
import io.dbtower.operator.model.BulkChangePlan;
import io.dbtower.operator.model.ChangeOutcome;
import io.dbtower.operator.model.ChangePlan;
import io.dbtower.operator.model.QueryResult;
import io.dbtower.operator.model.RevertPlan;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.CredentialPurpose;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import io.dbtower.operator.BackupCommands;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.JdbcConnectOptions;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.SqlCanonical;
import io.dbtower.operator.model.IndexAdvice;
import io.dbtower.operator.model.LatencyPercentile;
import io.dbtower.operator.model.RestoreVerification;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcOperator.class);

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

    /** 기종별 접속 조정 — 로그인 단계 드라이버 속성과 로그인 뒤 네트워크 읽기 제한. 기본은 URL에 건 값 그대로다. */
    protected JdbcConnectOptions connectOptions() {
        return JdbcConnectOptions.DEFAULT;
    }

    protected Connection open() throws SQLException {
        return pools.getConnection(instance, jdbcUrl(), connectOptions());
    }

    /**
     * 인스턴스+jdbcUrl에 매인 JdbcTemplate. DataSource는 ConnectionPools가 관리(풀 재사용)하므로
     * 매 호출 새로 만들어도 가볍다 — JdbcTemplate 자체는 상태 없는 얇은 래퍼다.
     *
     * A9: 모든 JDBC 조회에 기본 쿼리 타임아웃을 건다(setQueryTimeout=JDBC Statement.setQueryTimeout).
     * 진단 도구가 실수로 무거운 쿼리를 던져도 대상 DB를 오래 붙잡지 않게 — "진단이 부하 유발자가
     * 되면 안 된다"는 원칙. 개별 메서드(explainAnalyze 등)가 더 짧게 덮어쓸 수 있다.
     */
    protected JdbcTemplate jdbc() {
        JdbcTemplate t = new JdbcTemplate(pools.getDataSource(instance, jdbcUrl(), connectOptions()));
        t.setQueryTimeout(pools.queryTimeoutSeconds());
        return t;
    }

    @Override
    public HealthStatus health() {
        long start = System.currentTimeMillis();
        try {
            // 등록 검증(id==null)도 이 경로 — DriverManagerDataSource 1회용 커넥션으로 붙어본다.
            // 접속 실패는 JDBC의 SQLException이 아니라 Spring의 DataAccessException으로 올라온다.
            String version = jdbc().queryForObject(versionSql(), String.class);
            return HealthStatus.up(version != null ? version : "unknown", System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            // DataAccessException만 잡으면 안 된다(실측): 대상이 죽어 있으면 첫 커넥션 시도에서
            // HikariPool$PoolInitializationException이 나는데 이건 순수 RuntimeException이라
            // 게이트를 빠져나가 컨트롤러까지 올라갔다. 그 결과 /health가 down 상태가 아니라
            // 302(로그인 리다이렉트)를 냈고, 더 나쁘게는 다운 감지가 health()를 부르는 자리에서
            // 예외로 터져 "다운 알림"이 다시 침묵했다.
            //
            // health()의 계약은 "떠 있나 아닌가"다 — 어떤 이유로 실패하든 답은 down이다.
            // 응답에는 분류된 사유만 싣고(화면에 드라이버 영문이 그대로 보이던 것, B9) 원문은 서버 로그에 남긴다.
            // instance는 조회 경로가 예외로 터졌을 때 null일 수 있다(HealthFailureTest) — 그때도 health()는 던지지 않는다
            log.warn("헬스체크 실패 instance={} 원인={}", instance == null ? "?" : instance.getName(), failureMessage(e));
            return HealthStatus.down(e);
        }
    }

    /**
     * 풀이 첫 연결을 늦게 열면(134절) 호출자가 받는 예외는 "커넥션을 못 얻었다"뿐이고 드라이버가 준 실제 사유는 원인 사슬 끝에 있다 —
     * 다운 알림이 이유를 잃지 않게 가장 안쪽 사유를 덧붙인다.
     */
    static String failureMessage(Throwable e) {
        String top = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == e || root.getMessage() == null || top.contains(root.getMessage())) {
            return top;
        }
        return top + ": " + root.getMessage();
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

    /**
     * 데이터를 바꾸는 키워드 — 단어 경계로 본다. 식별자 일부({@code updated_at}·{@code audit_delete_log})는
     * 앞뒤가 {@code _}(단어 문자)라 경계가 성립하지 않아 걸리지 않고, 인용된 식별자는 canonical에서 지워진다.
     * WITH가 허용되면서 PostgreSQL의 data-modifying CTE
     * ({@code WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x})가 SELECT처럼 위장할 수 있어 필요하다.
     *
     * <p>전부 전 기종 예약어만 남긴다 — 맨 컬럼명으로 쓰이려면 인용이 필요해 오탐이 나지 않는다.
     * {@code cluster}·{@code set}·{@code call}·{@code copy}처럼 컬럼명으로 흔한 것은 넣지 않는다
     * (이 저장소의 {@code DatabaseInstance}에도 {@code cluster} 필드가 있다). 앞의 두 검사가 이미
     * "select/with로 시작 + 다중문 없음"을 강제하므로, 여기서 막아야 할 것은 CTE·서브쿼리에 숨는 형태뿐이다.
     */
    private static final java.util.regex.Pattern MUTATING = java.util.regex.Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|truncate|drop|alter|grant|revoke)\\b");

    /**
     * EXPLAIN에 절대 필요 없으면서 부작용이 확실한 형태 — 텍스트로 명확히 식별되는 것만 막는다.
     * {@code FOR UPDATE}/{@code FOR SHARE}는 운영 테이블에 락을 걸고,
     * {@code INTO OUTFILE}/{@code DUMPFILE}은 서버에 파일을 쓴다.
     */
    private static final java.util.regex.Pattern SIDE_EFFECTING = java.util.regex.Pattern.compile(
            "(?i)\\bfor\\s+(update|share|no\\s+key\\s+update|key\\s+share)\\b"
                    + "|\\binto\\s+(outfile|dumpfile)\\b");

    /**
     * explain 대상은 읽기 전용 조회만 허용한다 — 관리 플랫폼이 임의 DML을 실행하면 안 되기 때문.
     *
     * <p>판정은 원문이 아니라 {@link #canonical(String)}로 <b>주석과 인용 구간을 지운 사본</b>에서 한다.
     * 이전 구현은 홑따옴표만 추적해서, 주석 안의 아포스트로피 하나가 "문자열 안" 상태를 켜버리면
     * 그 뒤 세미콜론 검사가 통째로 무력화됐다 — {@code SELECT 1 /* ' *&#47;; DROP TABLE x}가 통과했다.
     * 반대로 {@code SELECT 1 /* ; *&#47;}처럼 주석 안의 세미콜론은 거부되는 오탐도 있었다. 양방향 파손이었다.
     *
     * <p>같은 저장소의 {@code LakehouseController}가 이미 "주석을 걷어낸 뒤 판정"을 쓰고 있었다.
     * 두 read-only 게이트가 서로 다른 강도로 병존하던 것을 여기서 맞춘다.
     *
     * <p>CTE({@code WITH ... SELECT})도 허용한다 — 실무 SQL의 상당 비중인데 거부되고 있었다.
     * 대신 데이터 변경 키워드가 보이면 거부해 data-modifying CTE를 막는다.
     *
     * <p><b>한계(정직 표기)</b>: 텍스트 게이트는 volatile 함수의 부작용
     * ({@code SELECT pg_terminate_backend(pid)}, autonomous transaction PL/SQL)을 막지 못한다.
     * 그건 대상 DB에서 읽기 전용 계정·트랜잭션으로 막아야 한다.
     */
    protected void requireSelect(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("EXPLAIN은 SELECT 쿼리만 허용합니다");
        }
        String canonical = canonical(sql);
        String head = canonical.stripLeading().toLowerCase();
        if (!head.startsWith("select") && !head.startsWith("with")) {
            throw new IllegalArgumentException("EXPLAIN은 SELECT 쿼리만 허용합니다");
        }
        if (hasStatementSeparator(canonical)) {
            throw new IllegalArgumentException("EXPLAIN은 단일 SELECT 문만 허용합니다 (다중문 불가)");
        }
        // 구체적인 쪽을 먼저 본다 — FOR UPDATE는 MUTATING의 update에도 걸려서, 순서가 반대면
        // "락을 건다"가 아니라 "데이터를 변경한다"는 덜 정확한 메시지가 나간다.
        if (SIDE_EFFECTING.matcher(canonical).find()) {
            throw new IllegalArgumentException(
                    "EXPLAIN은 락·파일 쓰기를 유발하는 절을 허용하지 않습니다 (FOR UPDATE / INTO OUTFILE 등)");
        }
        if (MUTATING.matcher(canonical).find()) {
            throw new IllegalArgumentException("EXPLAIN은 데이터를 변경하지 않는 조회만 허용합니다");
        }
    }

    /** 판정용 정규화 — 규칙과 근거는 {@link SqlCanonical}. 워크벤치 문장 분류기와 같은 규칙을 쓰려고 옮겼다. */
    static String canonical(String sql) {
        return SqlCanonical.canonical(sql);
    }

    private static boolean hasStatementSeparator(String canonical) {
        return SqlCanonical.hasStatementSeparator(canonical);
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

    /**
     * 워크벤치 콘솔 조회 — 계약은 {@link DbmsOperator#executeReadOnly}.
     *
     * <p>읽기 전용은 JDBC 표준 {@code setReadOnly(true)}로 건다. MySQL Connector/J는 세션 READ ONLY로, pgjdbc는
     * BEGIN READ ONLY로, Oracle JDBC는 SET TRANSACTION READ ONLY로 번역한다 — 기종 분기 없이 드라이버가 흡수한다.
     * SQL Server 드라이버는 이 힌트를 무시하므로 그 기종의 경계는 콘솔 계정 권한뿐이다. 그래서 끝은 항상 롤백한다:
     * 트랜잭션 안으로 새어 들어간 쓰기가 있어도 커밋되지 않는다(MySQL DDL의 암묵 커밋은 예외라 분류기가 먼저 막는다).
     */
    @Override
    public QueryResult executeReadOnly(ConsoleCredential credential, String statement, int rowCap, int timeoutSeconds) {
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("실행할 문장이 비었습니다");
        }
        if (hasStatementSeparator(canonical(statement))) {
            throw new IllegalArgumentException("콘솔은 한 번에 한 문장만 실행합니다");
        }
        int cap = DbmsOperator.clampLimit(rowCap);
        long start = System.nanoTime();
        try (Connection c = pools.getConsoleDataSource(instance, jdbcUrl(), CredentialPurpose.READ, credential, connectOptions())
                .getConnection()) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            try (Statement st = c.createStatement()) {
                beginReadOnly(st);
                st.setQueryTimeout(Math.max(1, timeoutSeconds));
                // 상한+1행까지만 받아 잘림 여부를 판정한다 — 나머지 행은 드라이버가 서버 쪽에서 끊는다
                st.setMaxRows(cap + 1);
                if (!st.execute(withoutTrailingSemicolon(statement))) {
                    return new QueryResult(List.of(), List.of(), false, (System.nanoTime() - start) / 1_000_000);
                }
                try (ResultSet rs = st.getResultSet()) {
                    return JdbcValues.read(rs, cap, start);
                }
            } finally {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // 커넥션이 깨졌다면 풀이 버린다 — 원래 예외를 롤백 실패가 덮지 않게 한다
                }
            }
        } catch (SQLException | RuntimeException e) {
            throw new OperatorException(instance.getType() + " 콘솔 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 드라이버가 {@code setReadOnly(true)}를 서버의 읽기 전용 트랜잭션으로 번역하지 않는 기종이 트랜잭션 첫 문장으로 직접 건다.
     * 실측(VERIFICATION 128절): MySQL·PostgreSQL 드라이버는 번역한다(콘솔 실행 안에서 @@transaction_read_only=1,
     * transaction_read_only=on). Oracle JDBC는 번역하지 않아 쓰기 권한 계정의 INSERT가 들어갔고 끝의 롤백만 막고 있었다.
     */
    protected void beginReadOnly(Statement st) throws SQLException {
    }

    /** 승인된 변경 실행 — 흐름과 불변식은 {@link JdbcChangeRunner}, 기종 차이는 아래 세 훅이 흡수한다. */
    @Override
    public ChangeOutcome executeChange(ConsoleCredential credential, ChangePlan plan) {
        try (Connection c = writeConnection(credential)) {
            return changeRunner().execute(c, plan);
        } catch (SQLException e) {
            throw new OperatorException(instance.getType() + " 변경 실행 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 대량 일괄 변경 — 배치마다 커넥션을 새로 얻는다. 한 커넥션을 붙잡고 수천 배치를 도는 동안 대상이 재시작하거나
     * 유휴 타임아웃에 끊기면 그 뒤 배치가 전부 실패한다. 배치 사이에는 어차피 쉬는 간격이 있어 재연결 비용이 묻힌다.
     */
    @Override
    public java.util.List<Object> nextBulkBoundary(ConsoleCredential credential, BulkChangePlan plan,
                                                   java.util.List<Object> lastKey) {
        try (Connection c = writeConnection(credential)) {
            return bulkRunner().nextBoundary(c, plan, lastKey);
        } catch (SQLException e) {
            throw new OperatorException(instance.getType() + " 배치 경계 조회 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public BulkBatchOutcome executeBulkBatch(ConsoleCredential credential, BulkChangePlan plan,
                                             java.util.List<Object> fromKey, java.util.List<Object> toKey) {
        try (Connection c = writeConnection(credential)) {
            return bulkRunner().executeBatch(c, plan, fromKey, toKey);
        } catch (SQLException e) {
            throw new OperatorException(instance.getType() + " 배치 실행 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public long countRows(ConsoleCredential credential, String table, String where, int timeoutSeconds) {
        String sql = "SELECT COUNT(*) FROM " + table + (where == null || where.isBlank() ? "" : " WHERE " + where);
        try (Connection c = writeConnection(credential);
             java.sql.Statement st = c.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            try (java.sql.ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new OperatorException(instance.getType() + " 행 수 세기 실패: " + e.getMessage(), e);
        }
    }

    private JdbcBulkChangeRunner bulkRunner() {
        return new JdbcBulkChangeRunner(new JdbcBulkChangeRunner.Dialect() {
            @Override
            public void beginChange(Statement st, int timeoutSeconds) throws SQLException {
                AbstractJdbcOperator.this.beginChange(st, timeoutSeconds);
            }

            @Override
            public String limitClause(int rows) {
                return AbstractJdbcOperator.this.bulkLimitClause(rows);
            }

            @Override
            public String selectHead(int rows) {
                return AbstractJdbcOperator.this.bulkSelectHead(rows);
            }
        });
    }

    /** 경계 조회의 행 수 제한 — 기본은 표준에 가까운 {@code LIMIT}이고 기종이 덮어쓴다. */
    protected String bulkLimitClause(int rows) {
        return " LIMIT " + rows;
    }

    /** {@code SELECT} 바로 뒤에 들어갈 것 — 기본은 없다. */
    protected String bulkSelectHead(int rows) {
        return "";
    }

    @Override
    public RevertPlan.Outcome revertChange(ConsoleCredential credential, RevertPlan plan) {
        try (Connection c = writeConnection(credential)) {
            return changeRunner().revert(c, plan);
        } catch (SQLException e) {
            throw new OperatorException(instance.getType() + " 되돌리기 실패: " + e.getMessage(), e);
        }
    }

    private Connection writeConnection(ConsoleCredential credential) throws SQLException {
        return pools.getConsoleDataSource(instance, jdbcUrl(), CredentialPurpose.WRITE, credential, connectOptions()).getConnection();
    }

    private JdbcChangeRunner changeRunner() {
        return new JdbcChangeRunner(new JdbcChangeRunner.Dialect() {
            @Override
            public void beginChange(Statement st, int timeoutSeconds) throws SQLException {
                AbstractJdbcOperator.this.beginChange(st, timeoutSeconds);
            }

            @Override
            public String lockedSelect(String from, String where, int timeoutSeconds) {
                return AbstractJdbcOperator.this.lockedSelect(from, where, timeoutSeconds);
            }

            @Override
            public String explain(Connection c, String sql) throws SQLException {
                return explainInTransaction(c, sql);
            }

            @Override
            public void beforeExplicitKeyInsert(Connection c, String table) throws SQLException {
                AbstractJdbcOperator.this.beforeExplicitKeyInsert(c, table);
            }

            @Override
            public void afterExplicitKeyInsert(Connection c, String table) throws SQLException {
                AbstractJdbcOperator.this.afterExplicitKeyInsert(c, table);
            }
        });
    }

    /** 변경 전 사본을 락과 함께 읽는 조회. 기본은 표준 락 절을 끝에 붙인다 — 락이 테이블 뒤 힌트로 오는 기종이 덮어쓴다 */
    protected String lockedSelect(String from, String where, int timeoutSeconds) {
        return "SELECT * FROM " + from + (where == null || where.isBlank() ? "" : " " + where) + lockClause(timeoutSeconds);
    }

    /** 자동 증가 열에 명시 키 값을 넣기 전후. 기본은 아무것도 안 한다 — PostgreSQL·MySQL·Oracle(BY DEFAULT)은 명시 값을 받는다 */
    protected void beforeExplicitKeyInsert(Connection c, String table) throws SQLException {
    }

    protected void afterExplicitKeyInsert(Connection c, String table) throws SQLException {
    }

    /**
     * 변경 트랜잭션 첫머리에서 락 대기 상한을 건다. 기본은 아무것도 하지 않는다 — 문장 타임아웃만으로는 락 대기가
     * 끊기지 않는 기종(InnoDB 기본 50초, 메타데이터 락 기본 1년)이 덮어쓴다.
     */
    protected void beginChange(Statement st, int timeoutSeconds) throws SQLException {
    }

    /** 변경 전 사본 조회에 붙이는 행 락 절 */
    protected String lockClause(int timeoutSeconds) {
        return " FOR UPDATE";
    }

    /**
     * 변경과 같은 커넥션·트랜잭션 안에서 실행계획을 본다. 모니터 풀의 explain은 다른 커넥션이라 커밋 전 변경(드라이런의
     * 인덱스 생성 등)이 보이지 않는다 — 전후 비교는 같은 트랜잭션 안에서 재야 의미가 있다.
     */
    protected String explainInTransaction(Connection c, String sql) throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(explainPrefix() + sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= n; i++) {
                    out.append(i > 1 ? " | " : "").append(rs.getString(i));
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    protected String explainPrefix() {
        return "EXPLAIN ";
    }

    /** Oracle JDBC는 끝 세미콜론을 문법 오류(ORA-00911)로 본다 — 사람이 흔히 붙이는 종결자만 걷어낸다. */
    static String withoutTrailingSemicolon(String sql) {
        String s = sql.stripTrailing();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).stripTrailing();
        }
        return s;
    }
}
