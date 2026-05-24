package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 백업 명령 렌더링의 주입 방어 검증.
 * 등록 API로 들어온 값이 그대로 셸 인자가 되는 자리라, 방어가 뚫리면
 * 관리 플랫폼이 임의 명령 실행 통로가 된다 — 회귀하면 안 되는 코드.
 */
class BackupCommandsTest {

    private DatabaseInstance instance(String host, String user, String db) {
        return new DatabaseInstance("test", DbmsType.MYSQL, host, 3306, db, user, "secret");
    }

    @Test
    void 플레이스홀더를_인스턴스_값으로_치환한다() {
        List<String> command = BackupCommands.render(
                "mysqldump -h {host} -P {port} -u {user} --databases {db}",
                instance("db.internal", "root", "sample"));
        assertEquals(List.of("mysqldump", "-h", "db.internal", "-P", "3306",
                "-u", "root", "--databases", "sample"), command);
    }

    @Test
    void 값에_공백을_넣어_인자를_추가하는_시도를_거부한다() {
        // "sample --result-file=/etc/passwd" 같은 값이 통과하면 인자 주입이 된다
        assertThrows(OperatorException.class, () -> BackupCommands.render(
                "mysqldump --databases {db}",
                instance("h", "root", "sample --result-file=/tmp/x")));
    }

    @Test
    void 대시로_시작하는_값은_플래그_주입이므로_거부한다() {
        assertThrows(OperatorException.class, () -> BackupCommands.render(
                "mysqldump -u {user}", instance("h", "--version", "db")));
    }

    @Test
    void 허용_문자_밖의_값을_거부한다() {
        assertThrows(OperatorException.class, () -> BackupCommands.render(
                "mysqldump -h {host}", instance("host;rm -rf /", "root", "db")));
        assertThrows(OperatorException.class, () -> BackupCommands.render(
                "mysqldump -h {host}", instance("$(whoami)", "root", "db")));
    }

    @Test
    void 템플릿에_password_플레이스홀더가_있으면_거부한다() {
        // 비밀번호는 argv에 실리면 ps로 노출된다 — env/stdin 전달만 허용
        assertThrows(OperatorException.class, () -> BackupCommands.render(
                "mysqldump -u {user} -p{password}", instance("h", "root", "db")));
    }

    @Test
    void stdin_YAML은_개행_주입을_거부하고_따옴표를_이스케이프한다() {
        // 개행이 통과하면 "password: x\nuri: mongodb://공격자" 같은 설정 키 주입이 된다
        assertThrows(OperatorException.class,
                () -> BackupCommands.yamlEntry("password", "x\nuri: mongodb://evil"));
        assertEquals("password: 'it''s'\n", BackupCommands.yamlEntry("password", "it's"));
    }

    @Test
    void 파일명은_안전한_문자만_남긴다() {
        assertEquals("a_b_c_.._etc", BackupCommands.safeFileName("a b/c;../etc"));
    }
}
