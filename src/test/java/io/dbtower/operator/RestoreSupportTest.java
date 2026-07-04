package io.dbtower.operator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 복원 검증 공통 유틸의 순수 로직 검증 — 라이브 DB 없이 도는 부분만.
 * 임시 대상 이름의 안전성, docker 컨테이너 파싱, 카운트/덤프 필터링이 회귀하면
 * "원본을 덮어쓰지 않는다"는 안전 불변식이 무너지므로 못 박아 둔다.
 */
class RestoreSupportTest {

    @Test
    void 임시_대상_이름은_격리_접두사와_안전한_문자만_쓴다() {
        String name = RestoreSupport.verifyTargetName();
        assertTrue(name.startsWith("dbtower_verify_"), name);
        // 생성한 이름은 그대로 SQL 식별자/네임스페이스가 되므로 심층 방어를 통과해야 한다
        assertDoesNotThrow(() -> RestoreSupport.requireSafeName(name));
    }

    @Test
    void 안전하지_않은_임시_이름은_거부한다() {
        assertThrows(OperatorException.class,
                () -> RestoreSupport.requireSafeName("evil`; DROP DATABASE x;--"));
        assertThrows(OperatorException.class, () -> RestoreSupport.requireSafeName("has space"));
        assertThrows(OperatorException.class, () -> RestoreSupport.requireSafeName(null));
    }

    @Test
    void docker_exec에서_컨테이너_이름을_뽑되_값을_받는_플래그를_건너뛴다() {
        // -e MYSQL_PWD의 값(MYSQL_PWD)을 컨테이너로 오인하면 안 된다
        assertEquals("dbtower-mysql", RestoreSupport.dockerContainer(List.of(
                "docker", "exec", "-i", "-e", "MYSQL_PWD", "dbtower-mysql", "mysql", "-u", "root")));
        assertEquals("dbtower-mongo", RestoreSupport.dockerContainer(List.of(
                "docker", "exec", "-i", "dbtower-mongo", "mongorestore", "--archive")));
    }

    @Test
    void docker_exec가_아니면_컨테이너를_특정할_수_없다() {
        assertThrows(OperatorException.class,
                () -> RestoreSupport.dockerContainer(List.of("mysql", "-u", "root")));
    }

    @Test
    void 카운트_출력에서_첫_정수를_뽑고_실패면_불명() {
        assertEquals(7, RestoreSupport.parseCount(new RestoreSupport.ExecResult(0, "7\n", "")));
        assertEquals(-1, RestoreSupport.parseCount(new RestoreSupport.ExecResult(1, "7", "boom")));
        assertEquals(-1, RestoreSupport.parseCount(new RestoreSupport.ExecResult(0, "no number", "")));
    }

    @Test
    void 덤프에서_원본_DB를_지정하는_행을_제거한다() throws Exception {
        // 이 필터가 뚫리면 복원이 임시 DB가 아니라 원본으로 흘러 데이터를 덮어쓴다 — 안전의 핵심
        Path dump = Files.createTempFile("verify", ".sql");
        Files.writeString(dump, String.join("\n",
                "-- MySQL dump",
                "CREATE DATABASE /*!32312 IF NOT EXISTS*/ `sample` DEFAULT CHARACTER SET utf8mb4;",
                "USE `sample`;",
                "CREATE TABLE t (id INT);",
                "INSERT INTO t VALUES (1);"));
        String filtered = new String(RestoreSupport.stripDatabaseSelection(dump));
        assertFalse(filtered.contains("CREATE DATABASE"), filtered);
        assertFalse(filtered.contains("USE `sample`"), filtered);
        assertTrue(filtered.contains("CREATE TABLE t"));
        assertTrue(filtered.contains("INSERT INTO t"));
        Files.deleteIfExists(dump);
    }
}
