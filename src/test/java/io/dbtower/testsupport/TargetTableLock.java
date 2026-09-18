package io.dbtower.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 같은 대상 DB에 테이블을 만드는 테스트가 서로를 지우지 않게 하는 자문 락(#112).
 *
 * <p>왜 필요한가: 실험·통합 테스트가 대상 DB에 <b>고정 이름</b> 테이블을 만든다(`exp_mask_*`, `bulk_scale`, `bulk_it` 등).
 * 워크트리가 다른 두 곳에서 같은 테스트를 돌리면 한쪽이 10만 행을 시딩하는 동안 다른 쪽의 {@code DROP}이 그 테이블을 지운다.
 * 2026-09-18에 실제로 겪었고 네 번 중 세 번이 이렇게 깨졌다 — 증상이 "테이블이 없다"라 처음에는 제품·테스트 결함처럼 보인다.
 *
 * <pre>
 * java.sql.BatchUpdateException: Table 'sample.exp_mask_orders' doesn't exist
 * org.postgresql.util.PSQLException: relation "exp_mask_customers" does not exist
 * </pre>
 *
 * <p>왜 이름에 실행 식별자를 붙이지 않는가: 테이블 이름이 실행계획 원문에 찍혀 기록으로 남는다. 회차마다 이름이 달라지면
 * 이전 결과와 나란히 읽기 어려워진다. 이름은 그대로 두고 <b>뒤에 온 실행이 기다리게</b> 한다.
 *
 * <p>락은 세션에 붙는다 — 연결을 열어 두는 동안만 유효하고 닫으면 자동으로 풀린다. 그래서 테스트가 중간에 죽어도
 * 락이 남지 않는다(파일·테이블 기반 락과 다른 점이다).
 */
public final class TargetTableLock implements AutoCloseable {

    /** 락 이름 — 대상 DB를 쓰는 테스트가 모두 같은 이름을 쓴다. PostgreSQL은 숫자 키라 이 이름의 해시를 쓴다. */
    private static final String LOCK_NAME = "dbtower-experiment-target";
    private static final int WAIT_SECONDS = 1800;

    private final List<Connection> held = new ArrayList<>();

    private TargetTableLock() {
    }

    /**
     * 대상 DB들의 락을 모두 잡을 때까지 기다린다. 어느 하나라도 실패하면 이미 잡은 것을 풀고 예외를 던진다 —
     * 절반만 잡은 채로 진행하면 락이 없는 것과 같다.
     *
     * @param jdbcUrls 락을 잡을 대상의 JDBC URL·계정. 같은 인스턴스를 두 번 넘겨도 된다(중복은 그대로 잡힌다)
     */
    public static TargetTableLock acquire(List<Target> targets) {
        TargetTableLock lock = new TargetTableLock();
        try {
            for (Target t : targets) {
                lock.held.add(lockOne(t));
            }
            return lock;
        } catch (RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    public record Target(String jdbcUrl, String username, String password) {
    }

    /**
     * 게이트 환경변수가 켜졌을 때만 잡는다. 꺼져 있으면 {@code null}을 돌려준다.
     *
     * <p>왜 필요한가: JUnit은 <b>메서드 수준</b> {@code @EnabledIfEnvironmentVariable}을 평가하기 전에
     * {@code @BeforeAll}을 돌린다. 대상 DB가 없는 CI에서 락을 잡으려다 연결 오류로 클래스가 통째로 깨졌다
     * (#112를 고치다 만든 회귀 — CI에는 MySQL·PostgreSQL 컨테이너가 없다).
     */
    public static TargetTableLock acquireIfEnabled(String gateEnv, List<Target> targets) {
        return "1".equals(System.getenv(gateEnv)) ? acquire(targets) : null;
    }

    private static Connection lockOne(Target t) {
        Connection c = null;
        try {
            c = DriverManager.getConnection(t.jdbcUrl(), t.username(), t.password());
            if (t.jdbcUrl().startsWith("jdbc:postgresql:")) {
                // PostgreSQL 자문 락은 bigint 키다 — 이름 해시를 키로 쓴다. 세션이 닫히면 풀린다
                try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_lock(?)")) {
                    ps.setLong(1, LOCK_NAME.hashCode());
                    ps.execute();
                }
            } else if (t.jdbcUrl().startsWith("jdbc:mysql:")) {
                try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                    ps.setString(1, LOCK_NAME);
                    ps.setInt(2, WAIT_SECONDS);
                    try (ResultSet rs = ps.executeQuery()) {
                        // 1=잡았다, 0=기다리다 시간 초과, NULL=오류. 못 잡았으면 기다린 의미가 없으므로 멈춘다
                        if (!rs.next() || rs.getInt(1) != 1) {
                            throw new IllegalStateException("대상 DB 자문 락을 " + WAIT_SECONDS + "초 안에 잡지 못했다: "
                                    + t.jdbcUrl() + " — 다른 실행이 아직 이 대상을 쓰고 있다");
                        }
                    }
                }
            } else {
                // 락 수단이 없는 기종은 잡지 않는다. 연결만 열어 두고 close에서 함께 닫는다
                return c;
            }
            return c;
        } catch (SQLException e) {
            closeQuietly(c);
            throw new IllegalStateException("대상 DB 자문 락 실패: " + t.jdbcUrl() + " — " + e.getMessage(), e);
        } catch (RuntimeException e) {
            closeQuietly(c);
            throw e;
        }
    }

    /** 연결을 닫으면 세션 락이 자동으로 풀린다 — 풀기 문장을 따로 보내지 않는다. */
    @Override
    public void close() {
        held.forEach(TargetTableLock::closeQuietly);
        held.clear();
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (SQLException ignored) {
            // 락은 연결이 끊기면 서버가 푼다 — 닫기 실패가 테스트 결과를 덮지 않는다
        }
    }
}
