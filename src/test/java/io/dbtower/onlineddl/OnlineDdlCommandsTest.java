package io.dbtower.onlineddl;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * gh-ost 명령 렌더링·검증의 안전 규칙을 코드로 못 박는다.
 * 핵심: 비밀번호가 argv에 절대 실리지 않는다, 식별자/ALTER 화이트리스트, ALTER TABLE 외 문장 차단.
 */
class OnlineDdlCommandsTest {

    private DatabaseInstance mysql(String db) {
        return new DatabaseInstance("m", DbmsType.MYSQL, "127.0.0.1", 13306, db, "root", "s3cr3t-pw");
    }

    @Test
    void 비밀번호는_argv에_실리지_않고_conf_경로만_들어간다() {
        DatabaseInstance instance = mysql("sample");
        Path conf = Path.of("/tmp/fake.cnf");
        List<String> cmd = OnlineDdlCommands.buildArgs(List.of("gh-ost"),
                OnlineDdlCommands.flagsFrom("--allow-on-master --assume-rbr"),
                instance, "b4_demo", "ADD COLUMN x INT NULL", conf, false);

        // 비밀번호 값이 어떤 인자에도 없어야 한다
        assertTrue(cmd.stream().noneMatch(a -> a.contains("s3cr3t-pw")),
                "비밀번호가 argv에 노출됨: " + cmd);
        assertTrue(cmd.contains("--conf=/tmp/fake.cnf"));
        assertTrue(cmd.contains("--table=b4_demo"));
        assertTrue(cmd.contains("--alter=ADD COLUMN x INT NULL"));
        assertFalse(cmd.contains("--execute"), "noop이면 --execute가 없어야 한다");
    }

    @Test
    void execute_true면_execute_플래그가_붙는다() {
        List<String> cmd = OnlineDdlCommands.buildArgs(List.of("gh-ost"), List.of(),
                mysql("sample"), "b4_demo", "ADD COLUMN x INT NULL", Path.of("/tmp/c.cnf"), true);
        assertTrue(cmd.contains("--execute"));
    }

    @Test
    void 식별자와_ALTER는_화이트리스트로_주입을_막는다() {
        // 세미콜론으로 문장 스택 시도 → 거부
        assertThrows(IllegalArgumentException.class,
                () -> OnlineDdlCommands.validateAlter("ADD COLUMN x INT; DROP TABLE users"));
        // 허용되지 않은 선두 키워드(DROP DATABASE 흉내 등) → 거부
        assertThrows(IllegalArgumentException.class,
                () -> OnlineDdlCommands.validateAlter("SELECT 1"));
        // 테이블 이름에 백틱/공백 → 거부
        assertThrows(IllegalArgumentException.class,
                () -> OnlineDdlCommands.validateIdentifier("테이블", "b4_demo`; DROP"));
        // 정상 케이스는 통과
        assertDoesNotThrow(() -> OnlineDdlCommands.validateAlter("ADD COLUMN x VARCHAR(32) NULL"));
        assertDoesNotThrow(() -> OnlineDdlCommands.validateIdentifier("테이블", "b4_demo"));
    }

    @Test
    void conf_파일은_소유자_전용_0600으로_생성된다() throws Exception {
        Path conf = OnlineDdlCommands.writeConf(mysql("sample"));
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(conf);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
                    "conf 파일은 소유자 read/write만 허용해야 한다");
            String body = Files.readString(conf);
            assertTrue(body.contains("password=s3cr3t-pw"), "conf 파일에는 비밀번호가 들어간다(이 파일만이 유일한 경로)");
        } finally {
            Files.deleteIfExists(conf);
        }
    }

    @Test
    void 고스트_테이블_이름을_출력에서_뽑는다() {
        String out = "# Migrating `sample`.`b4_demo`; Ghost table is `sample`.`_b4_demo_gho`\n# Done";
        assertEquals("_b4_demo_gho", OnlineDdlCommands.parseGhostTable(out));
        assertNull(OnlineDdlCommands.parseGhostTable("no ghost here"));
    }

    @Test
    void 없는_바이너리는_binaryAvailable이_false다() {
        assertFalse(OnlineDdlCommands.binaryAvailable(List.of("gh-ost-nonexistent-xyz")));
    }
}
