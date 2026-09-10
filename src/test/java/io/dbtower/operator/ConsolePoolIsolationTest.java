package io.dbtower.operator;

import com.zaxxer.hikari.HikariDataSource;
import io.dbtower.registry.ConsoleCredential;
import io.dbtower.registry.CredentialPurpose;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 콘솔 계정 풀이 모니터 풀과 섞이지 않는지 — 사람이 여는 조회의 권한이 수집기 경로로 새면 안 된다.
 * H2 메모리 DB로 실제 HikariCP 풀을 만들어 검증한다.
 */
class ConsolePoolIsolationTest {

    // 테스트마다 다른 메모리 DB를 쓴다 — H2는 첫 접속 계정이 그 DB의 관리자가 되므로 계정이 다른 풀끼리 DB를 공유하면 인증에 실패한다
    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 5000, 600_000, 1_800_000, 30, 60_000);

    @AfterEach
    void close() {
        pools.closeAll();
    }

    private static DatabaseInstance instance(long id) {
        DatabaseInstance instance = new DatabaseInstance("h2-" + id, DbmsType.POSTGRESQL, "localhost", 5432,
                "sample", "monitor", "");
        ReflectionTestUtils.setField(instance, "id", id);
        return instance;
    }

    @Test
    void 모니터_풀과_콘솔_풀은_다른_계정의_다른_풀이다() {
        DatabaseInstance db = instance(1);
        DataSource monitor = pools.getDataSource(db, "jdbc:h2:mem:iso_monitor");
        String console = "jdbc:h2:mem:iso_console";
        DataSource read = pools.getConsoleDataSource(db, console, CredentialPurpose.READ, new ConsoleCredential("sa", ""));
        DataSource write = pools.getConsoleDataSource(db, console, CredentialPurpose.WRITE, new ConsoleCredential("sa", ""));

        assertNotSame(monitor, read);
        assertNotSame(read, write, "조회와 변경도 같은 풀을 쓰지 않는다");
        assertEquals("monitor", ((HikariDataSource) monitor).getUsername());
        assertEquals("sa", ((HikariDataSource) read).getUsername());
        assertSame(read, pools.getConsoleDataSource(db, console, CredentialPurpose.READ, new ConsoleCredential("sa", "")),
                "같은 자격증명이면 풀을 재사용한다");
    }

    @Test
    void 콘솔_비밀번호가_바뀌면_옛_풀을_닫고_새로_만든다() {
        DatabaseInstance db = instance(2);
        HikariDataSource before = (HikariDataSource) pools.getConsoleDataSource(db, "jdbc:h2:mem:iso_rotate", CredentialPurpose.READ,
                new ConsoleCredential("sa", ""));
        HikariDataSource after = (HikariDataSource) pools.getConsoleDataSource(db, "jdbc:h2:mem:iso_rotate", CredentialPurpose.READ,
                new ConsoleCredential("sa", "rotated"));
        assertTrue(before.isClosed(), "옛 비밀번호의 풀이 남아 있으면 회수한 계정으로 계속 붙는다");
        assertNotSame(before, after);
    }

    @Test
    void 인스턴스를_닫으면_콘솔_풀도_함께_닫힌다() {
        DatabaseInstance db = instance(3);
        HikariDataSource read = (HikariDataSource) pools.getConsoleDataSource(db, "jdbc:h2:mem:iso_close", CredentialPurpose.READ,
                new ConsoleCredential("sa", ""));
        DatabaseInstance other = instance(33);
        HikariDataSource otherRead = (HikariDataSource) pools.getConsoleDataSource(other, "jdbc:h2:mem:iso_close", CredentialPurpose.READ,
                new ConsoleCredential("sa", ""));

        pools.close(3L);

        assertTrue(read.isClosed());
        assertFalse(otherRead.isClosed(), "id 접두가 겹치는 다른 인스턴스(33)는 건드리지 않는다");
    }

    @Test
    void 등록_전_인스턴스에는_콘솔_풀을_만들지_않는다() {
        DatabaseInstance unregistered = new DatabaseInstance("new", DbmsType.MYSQL, "h", 1, "d", "u", "p");
        assertThrows(IllegalArgumentException.class, () -> pools.getConsoleDataSource(unregistered, "jdbc:h2:mem:iso_close",
                CredentialPurpose.READ, new ConsoleCredential("sa", "")));
    }
}
