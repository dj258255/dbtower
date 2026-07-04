package io.dbtower.onlineddl;

import io.dbtower.registry.DatabaseInstance;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * gh-ost 명령 렌더링·검증·실행 유틸 — BackupCommands/RestoreSupport의 대칭.
 *
 * 보안 원칙:
 * - 식별자(스키마·테이블)는 화이트리스트([A-Za-z0-9_]+)만 통과 — 임의 SQL 주입 방지.
 * - ALTER 절은 gh-ost가 항상 "ALTER TABLE db.table &lt;절&gt;"로 감싸므로 DROP DATABASE 등 문장 교체가
 *   구조적으로 불가능하다. 그 위에 세미콜론/제어문자 거부 + 허용된 ALTER 연산 키워드로 시작하는지까지
 *   심층 방어한다.
 * - 비밀번호는 argv 금지.
 *
 * 비밀번호 전달의 한계(실측 2026-07-04, gh-ost 1.1.10):
 *   gh-ost의 비밀번호 입력 수단은 -password(argv), -ask-pass(대화형), -conf(파일) 셋뿐이다.
 *   MYSQL_PWD 같은 환경변수 경로가 없고(go-sql-driver 사용, libmysqlclient 아님),
 *   -ask-pass는 TTY에서만 동작한다(파이프로 넣으면 "inappropriate ioctl for device"로 즉시 실패 — 실측).
 *   따라서 argv를 피하는 유일한 수단은 소유자 전용(0600) 임시 conf 파일이다. argv는 ps로 전체
 *   프로세스에 노출되지만, 0600 임시 파일은 소유자만 읽을 수 있고 실행 직후 즉시 삭제하므로 엄격히 더 안전하다.
 *   비밀번호는 로그·argv·커밋 어디에도 남기지 않는다.
 */
final class OnlineDdlCommands {

    private OnlineDdlCommands() {
    }

    /** 스키마·테이블 식별자 — 백틱 없이 그대로 gh-ost 인자로 들어가므로 안전 문자만. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    /** 호스트는 IP 또는 호스트명 — argv에 들어가므로 플래그 주입('-' 시작)과 제어문자를 막는다. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]+");
    /** ALTER 절이 시작할 수 있는 연산 키워드(대소문자 무시). 이 안에서만 스키마 변경이 가능하다. */
    private static final Set<String> ALTER_VERBS = Set.of(
            "ADD", "DROP", "MODIFY", "CHANGE", "ALTER", "RENAME", "CONVERT", "ENGINE");

    static void validateIdentifier(String kind, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(kind + " 이름은 영문/숫자/밑줄만 허용합니다: " + value);
        }
    }

    /**
     * ALTER 절 검증. gh-ost는 이 값을 "ALTER TABLE db.table &lt;절&gt;"의 절 부분으로만 쓴다.
     * 세미콜론(문장 스택)·제어문자를 거부하고, 허용된 ALTER 연산 키워드로 시작해야 통과시킨다.
     */
    static void validateAlter(String alter) {
        if (alter == null || alter.isBlank()) {
            throw new IllegalArgumentException("ALTER 절이 비어 있습니다");
        }
        if (alter.indexOf(';') >= 0 || alter.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ALTER 절에 세미콜론/제어문자를 쓸 수 없습니다");
        }
        String firstWord = alter.trim().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        if (!ALTER_VERBS.contains(firstWord)) {
            throw new IllegalArgumentException(
                    "허용된 ALTER 연산(ADD/DROP/MODIFY/CHANGE/ALTER/RENAME/CONVERT/ENGINE)으로 시작해야 합니다: "
                            + firstWord);
        }
    }

    /**
     * gh-ost 실행 인자 조립. base(실행 파일)+flags(환경별 안전 플래그)+구조화 인자 순서.
     * 비밀번호는 여기 어디에도 없다 — user/password는 conf 파일에만 있고 argv엔 --conf 경로만 실린다.
     */
    static List<String> buildArgs(List<String> base, List<String> flags, DatabaseInstance instance,
                                  String table, String alter, Path confFile, boolean execute) {
        validateIdentifier("스키마", instance.getDbName());
        validateIdentifier("테이블", table);
        validateAlter(alter);
        if (instance.getHost() == null || !HOST.matcher(instance.getHost()).matches()) {
            throw new IllegalArgumentException("호스트 값이 안전하지 않습니다: " + instance.getHost());
        }
        List<String> cmd = new ArrayList<>(base);
        cmd.addAll(flags);
        cmd.add("--conf=" + confFile);
        cmd.add("--host=" + instance.getHost());
        cmd.add("--port=" + instance.getPort());
        cmd.add("--database=" + instance.getDbName());
        cmd.add("--table=" + table);
        cmd.add("--alter=" + alter);
        if (execute) {
            cmd.add("--execute");
        }
        return cmd;
    }

    /** 공백으로 토큰화(설정값 — 신뢰 소스). 빈 토큰은 버린다. */
    static List<String> tokenize(String template) {
        List<String> out = new ArrayList<>();
        for (String t : template.trim().split("\\s+")) {
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * gh-ost 접속용 conf 파일(my.cnf 형식)을 소유자 전용(0600)으로 만든다. user/password만 담는다.
     * 파일 권한을 생성 시점부터 0600으로 강제해 다른 사용자가 읽을 틈을 주지 않는다(POSIX 파일시스템 전제).
     */
    static Path writeConf(DatabaseInstance instance) {
        try {
            Path conf = Files.createTempFile("dbtower-ghost-", ".cnf",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            String body = "[client]\nuser=" + instance.getUsername()
                    + "\npassword=" + instance.getPassword() + "\n";
            Files.writeString(conf, body, StandardCharsets.UTF_8);
            return conf;
        } catch (IOException e) {
            throw new IllegalStateException("gh-ost 접속 설정 파일 생성 실패: " + e.getMessage(), e);
        }
    }

    /** 바이너리 존재 확인 — 없으면 UNSUPPORTED로 정직하게 보고하기 위한 사전 점검. */
    static boolean binaryAvailable(List<String> base) {
        try {
            List<String> probe = new ArrayList<>(base);
            probe.add("--version");
            Process p = new ProcessBuilder(probe).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false; // 실행 파일 없음
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    record ExecResult(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }

        /** 로그/응답에 싣기 좋게 마지막 부분만. 여기엔 비밀번호가 없다(argv/conf에만 있었고 stdout엔 안 나온다). */
        String tail(int max) {
            String s = output == null ? "" : output.trim();
            return s.length() > max ? s.substring(s.length() - max) : s;
        }
    }

    /**
     * gh-ost 실행 — stdout/stderr를 한데 모아 캡처한다(gh-ost는 진행 로그를 stderr로 낸다).
     * timeoutSeconds를 넘기면 프로세스를 파괴하고 실패로 본다(고스트 테이블만 남을 수 있어 정리 안내를 detail에 싣는다).
     */
    static ExecResult exec(List<String> command, long timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            byte[] out = process.getInputStream().readAllBytes();
            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return new ExecResult(-1, new String(out, StandardCharsets.UTF_8)
                        + "\n(시간 초과로 중단 — 고스트 테이블 잔여물 확인 필요)");
            }
            return new ExecResult(process.exitValue(), new String(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ExecResult(-1, "gh-ost 실행 실패: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new ExecResult(-1, "gh-ost 실행이 중단되었습니다");
        }
    }

    /** gh-ost 출력에서 고스트 테이블 이름(`_x_gho`)을 뽑는다 — 없으면 null. */
    static String parseGhostTable(String output) {
        if (output == null) {
            return null;
        }
        var m = Pattern.compile("Ghost table is `[^`]+`\\.`([^`]+)`").matcher(output);
        return m.find() ? m.group(1) : null;
    }

    static List<String> flagsFrom(String flags) {
        return flags == null || flags.isBlank() ? List.of() : Arrays.asList(flags.trim().split("\\s+"));
    }
}
