package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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

    @Override
    public HealthStatus health() {
        long start = System.currentTimeMillis();
        try (Connection conn = open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(versionSql())) {
            String version = rs.next() ? rs.getString(1) : "unknown";
            return HealthStatus.up(version, System.currentTimeMillis() - start);
        } catch (SQLException e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    @Override
    public ReplicationState replicationState() {
        throw new UnsupportedOperationException("확장2에서 구현 예정: " + instance.getType());
    }

    /** 외부 CLI(mysqldump/pg_dump)로 백업을 수행하고 stdout을 파일로 받는 공통 루틴 */
    protected BackupResult runCliBackup(java.util.List<String> command, java.util.Map<String, String> env,
                                        java.nio.file.Path outFile) {
        try {
            java.nio.file.Files.createDirectories(outFile.getParent());
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().putAll(env);
            pb.redirectOutput(outFile.toFile());
            pb.redirectErrorStream(false);
            Process process = pb.start();
            String stderr = new String(process.getErrorStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new OperatorException("백업 명령 실패(exit=" + exit + "): " + stderr.trim(), null);
            }
            return new BackupResult(outFile.toString(), java.nio.file.Files.size(outFile));
        } catch (OperatorException e) {
            throw e;
        } catch (Exception e) {
            throw new OperatorException("백업 실행 실패: " + e.getMessage(), e);
        }
    }

    protected String backupTimestamp() {
        return java.time.LocalDateTime.now().toString().replace(":", "-");
    }

    /**
     * 백업 명령 템플릿의 플레이스홀더를 인스턴스 값으로 치환한다.
     * 템플릿 방식을 쓰는 이유: 같은 mysqldump라도 실행 위치(호스트/컨테이너/원격 에이전트)에 따라
     * 접속 주소와 인자가 달라진다 — 그 환경 차이를 코드가 아니라 설정이 흡수하게 한다.
     *
     * 보안: 먼저 토큰으로 나눈 뒤 토큰 안에서만 치환한다(치환 후 split 금지 —
     * 값에 공백을 넣어 인자를 주입하는 것 방지). 치환 값은 허용 문자만 통과시키고
     * "-"로 시작하는 값(플래그 주입)을 거부한다. 비밀번호는 argv에 절대 싣지 않고
     * 환경변수(MYSQL_PWD/PGPASSWORD)로만 전달한다.
     */
    protected java.util.List<String> renderCommand(String template) {
        if (template.contains("{password}")) {
            throw new OperatorException(
                    "백업 명령에 {password}를 쓸 수 없습니다 — 비밀번호는 환경변수로 전달됩니다", null);
        }
        return java.util.Arrays.stream(template.split(" "))
                .filter(t -> !t.isBlank())
                .map(t -> t
                        .replace("{host}", safeValue(instance.getHost()))
                        .replace("{port}", String.valueOf(instance.getPort()))
                        .replace("{user}", safeValue(instance.getUsername()))
                        .replace("{db}", safeValue(instance.getDbName())))
                .toList();
    }

    private String safeValue(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+") || value.startsWith("-")) {
            throw new OperatorException("백업 명령에 쓸 수 없는 값: " + value, null);
        }
        return value;
    }

    /** 파일 경로에 들어가는 이름은 안전한 문자만 남긴다 (경로 탈출 방지) */
    protected String safeFileName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** explain 대상은 SELECT만 허용한다 — 관리 플랫폼이 임의 DML을 실행하면 안 되기 때문. */
    protected void requireSelect(String sql) {
        if (sql == null || !sql.trim().toLowerCase().startsWith("select")) {
            throw new IllegalArgumentException("EXPLAIN은 SELECT 쿼리만 허용합니다");
        }
    }
}
