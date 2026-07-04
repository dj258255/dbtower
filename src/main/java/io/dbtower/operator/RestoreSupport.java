package io.dbtower.operator;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 복원 검증 공통 유틸 — 백업(BackupCommands)의 대칭.
 *
 * 검증은 "명령을 실행하고 그 종료코드/출력으로 성공을 판정"하는 일이라, 백업의 파일 산출과 달리
 * stdout(복원 후 카운트)과 stderr(실패 원인)를 함께 캡처해야 한다. 여기에 임시 대상 이름 생성과
 * docker 컨테이너 파싱(아카이브 복사에 필요)을 모아 둔다.
 */
final class RestoreSupport {

    private RestoreSupport() {
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_]+");
    private static final Pattern FIRST_INT = Pattern.compile("-?\\d+");

    /** 원본과 절대 겹치지 않는 임시 대상 이름 — 소문자/숫자/밑줄만. 밀리초까지 넣어 연속 호출도 충돌하지 않게. */
    static String verifyTargetName() {
        return "dbtower_verify_" + LocalDateTime.now().format(TS);
    }

    /** 생성한 이름이 식별자로 안전한지 최종 방어 — SQL 식별자/네임스페이스에 그대로 들어가는 자리라 심층 방어. */
    static void requireSafeName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new OperatorException("복원 검증 임시 대상 이름이 안전하지 않습니다: " + name, null);
        }
    }

    /**
     * `docker exec [flags] <container> <bin> ...` 렌더 결과에서 컨테이너 이름을 뽑는다.
     * 아카이브를 컨테이너로 넣는 docker cp / 정리용 rm이 컨테이너 이름을 따로 필요로 하기 때문.
     * 값을 따로 받는 플래그(-e VAR 등)는 그 값까지 건너뛴다 — 값을 컨테이너로 오인하지 않게.
     */
    static String dockerContainer(List<String> command) {
        if (command.size() < 3 || !command.get(0).equals("docker") || !command.get(1).equals("exec")) {
            throw new OperatorException("docker exec 명령이 아니라 컨테이너를 특정할 수 없습니다: " + command, null);
        }
        int i = 2;
        while (i < command.size()) {
            String t = command.get(i);
            if (t.equals("-e") || t.equals("--env") || t.equals("-u") || t.equals("--user")
                    || t.equals("-w") || t.equals("--workdir")) {
                i += 2; // 플래그 + 그 값
                continue;
            }
            if (t.startsWith("-")) {
                i++;
                continue;
            }
            return t; // 플래그가 아닌 첫 토큰 = 컨테이너
        }
        throw new OperatorException("컨테이너 이름을 찾지 못했습니다: " + command, null);
    }

    static List<String> concat(List<String> base, String... extra) {
        List<String> out = new ArrayList<>(base);
        out.addAll(Arrays.asList(extra));
        return out;
    }

    /** 복원 후 카운트 쿼리의 stdout에서 첫 정수를 뽑는다. 실패했거나 숫자가 없으면 -1(불명). */
    static int parseCount(ExecResult result) {
        if (!result.ok() || result.stdout() == null) {
            return -1;
        }
        Matcher m = FIRST_INT.matcher(result.stdout().trim());
        return m.find() ? Integer.parseInt(m.group()) : -1;
    }

    /**
     * mysqldump --databases 덤프에서 원본 DB를 지정하는 행(CREATE DATABASE / USE)을 제거한다.
     * 이 행을 지우면 남은 CREATE TABLE/INSERT가 연결의 기본 DB(= 임시 DB)로만 적재된다 —
     * 원본 DB를 절대 덮어쓰지 않기 위한 핵심 안전장치.
     */
    static byte[] stripDatabaseSelection(Path dump) {
        try {
            List<String> lines = Files.readAllLines(dump, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                String head = line.trim().toUpperCase();
                if (head.startsWith("USE ") || head.startsWith("CREATE DATABASE")) {
                    continue;
                }
                sb.append(line).append('\n');
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OperatorException("덤프 파일 읽기 실패: " + e.getMessage(), e);
        }
    }

    record ExecResult(int exitCode, String stdout, String stderr) {
        boolean ok() {
            return exitCode == 0;
        }

        /** 실패 원인을 짧게 — stderr 우선, 없으면 stdout. 로그/응답에 싣기 좋게 뒷부분만. */
        String errorTail() {
            String s = (stderr == null || stderr.isBlank()) ? stdout : stderr;
            s = s == null ? "" : s.trim();
            return s.length() > 400 ? s.substring(s.length() - 400) : s;
        }
    }

    /**
     * stdout/stderr를 함께 캡처하는 실행. stdin(덤프)이 클 수 있어 별도 스레드로 흘려보내고,
     * stderr도 별도 스레드로 읽는다 — 한 파이프가 차서 서로 막히는 교착을 피하기 위해서다.
     */
    static ExecResult exec(List<String> command, Map<String, String> env, byte[] stdin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().putAll(env);
            Process process = pb.start();

            Thread writer = null;
            if (stdin != null) {
                writer = new Thread(() -> {
                    try (OutputStream os = process.getOutputStream()) {
                        os.write(stdin);
                    } catch (IOException ignored) {
                        // 프로세스가 먼저 끝나 파이프가 닫히는 경우 — 판정은 종료코드/stderr로 하므로 무시
                    }
                });
                writer.start();
            } else {
                process.getOutputStream().close();
            }

            byte[][] errBox = new byte[1][];
            Thread errReader = new Thread(() -> {
                try {
                    errBox[0] = process.getErrorStream().readAllBytes();
                } catch (IOException e) {
                    errBox[0] = new byte[0];
                }
            });
            errReader.start();

            byte[] out = process.getInputStream().readAllBytes();
            int code = process.waitFor();
            errReader.join();
            if (writer != null) {
                writer.join();
            }
            return new ExecResult(code,
                    new String(out, StandardCharsets.UTF_8),
                    new String(errBox[0] == null ? new byte[0] : errBox[0], StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperatorException("복원 검증 명령이 중단되었습니다", e);
        } catch (IOException e) {
            throw new OperatorException("복원 검증 명령 실행 실패: " + e.getMessage(), e);
        }
    }
}
