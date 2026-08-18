package io.dbtower.operator.internal;

import io.dbtower.operator.BackupCommands;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.IndexAdvice;
import io.dbtower.operator.model.LatencyPercentile;
import io.dbtower.operator.model.RestoreVerification;

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
     *
     * A9: 모든 JDBC 조회에 기본 쿼리 타임아웃을 건다(setQueryTimeout=JDBC Statement.setQueryTimeout).
     * 진단 도구가 실수로 무거운 쿼리를 던져도 대상 DB를 오래 붙잡지 않게 — "진단이 부하 유발자가
     * 되면 안 된다"는 원칙. 개별 메서드(explainAnalyze 등)가 더 짧게 덮어쓸 수 있다.
     */
    protected JdbcTemplate jdbc() {
        JdbcTemplate t = new JdbcTemplate(pools.getDataSource(instance, jdbcUrl()));
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
        } catch (DataAccessException e) {
            return HealthStatus.down(e.getMessage());
        }
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

    /**
     * 판정용 정규화 — 주석({@code --}, 중첩 가능한 블록 주석)과 인용 구간(문자열·식별자·달러 인용)을
     * 같은 길이의 공백으로 지운 사본을 만든다. 원문은 그대로 실행하고 판정만 이 사본으로 한다.
     *
     * <p>백슬래시는 <b>이스케이프로 보지 않는다</b>(PostgreSQL의 standard_conforming_strings=on 동작).
     * 이게 fail-closed인 이유: {@code 'a\'; DROP TABLE x}에서 백슬래시를 이스케이프로 보면 문자열이
     * 계속 이어진다고 판단해 세미콜론을 놓치지만(통과), 안 보면 문자열이 거기서 끝나 세미콜론을 발견한다(거부).
     * 놓치는 쪽보다 더 거부하는 쪽이 안전하다. 다만 PostgreSQL의 {@code E'...'}는 명세상 백슬래시가
     * 이스케이프라 그때만 예외로 처리한다 — {@code SELECT E'\''; DROP TABLE x}가 정확히 이 경로로 뚫렸었다.
     *
     * <p>인용이 닫히지 않으면 남은 전체를 삼키지만, 그런 SQL은 DB가 문법 오류로 거부하므로
     * "우리에게는 숨기면서 DB에서는 실행되는" 조합이 성립하지 않는다.
     */
    static String canonical(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {          // 라인 주석
                while (i < n && sql.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {          // 블록 주석 (PostgreSQL은 중첩 허용)
                int depth = 0;
                while (i < n) {
                    if (sql.charAt(i) == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                        depth++;
                        out.append("  ");
                        i += 2;
                    } else if (sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                        depth--;
                        out.append("  ");
                        i += 2;
                        if (depth == 0) {
                            break;
                        }
                    } else {
                        out.append(' ');
                        i++;
                    }
                }
                continue;
            }
            if (c == '$') {                                                   // 달러 인용 $tag$ ... $tag$
                int close = sql.indexOf('$', i + 1);
                if (close > i && isDollarTag(sql, i + 1, close)) {
                    String tag = sql.substring(i, close + 1);
                    int end = sql.indexOf(tag, close + 1);
                    int stop = end < 0 ? n : end + tag.length();
                    out.append(" ".repeat(stop - i));
                    i = stop;
                    continue;
                }
            }
            if (c == '\'' || c == '"' || c == '`') {                          // 문자열·식별자 인용
                boolean backslashEscapes = c == '\'' && isEscapeStringPrefix(sql, i);
                out.append(' ');
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (backslashEscapes && d == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    if (d == c) {
                        if (i + 1 < n && sql.charAt(i + 1) == c) {            // '' "" `` 는 이스케이프된 인용부호
                            out.append("  ");
                            i += 2;
                            continue;
                        }
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(' ');
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 달러 인용 태그는 비었거나 영숫자·밑줄만 (PostgreSQL 규약) */
    private static boolean isDollarTag(String sql, int from, int toExclusive) {
        for (int k = from; k < toExclusive; k++) {
            char c = sql.charAt(k);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    /** PostgreSQL의 E'...' — 이 안에서만 백슬래시가 이스케이프다. 앞 글자가 식별자면 E가 아니라 이름의 끝이다. */
    private static boolean isEscapeStringPrefix(String sql, int quoteIndex) {
        if (quoteIndex == 0) {
            return false;
        }
        char prev = sql.charAt(quoteIndex - 1);
        if (prev != 'E' && prev != 'e') {
            return false;
        }
        return quoteIndex < 2 || !(Character.isLetterOrDigit(sql.charAt(quoteIndex - 2))
                || sql.charAt(quoteIndex - 2) == '_');
    }

    /**
     * 문장 구분자 세미콜론이 문장 <b>중간</b>에 있는지 검사한다(입력은 canonical 사본).
     * 끝에 하나 붙은 세미콜론(뒤가 공백뿐)은 정상 종결로 허용한다.
     */
    private static boolean hasStatementSeparator(String canonical) {
        int idx = canonical.indexOf(';');
        while (idx >= 0) {
            if (!canonical.substring(idx + 1).isBlank()) {
                return true;
            }
            idx = canonical.indexOf(';', idx + 1);
        }
        return false;
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
