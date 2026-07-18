package io.dbtower.operator;

import java.lang.ProcessBuilder.Redirect;
import io.dbtower.operator.model.BackupResult;
import io.dbtower.registry.DatabaseInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 외부 CLI 백업 실행 유틸. 원래 AbstractJdbcOperator 안에 있었지만
 * MongoDB(비 JDBC) 추가 때 분리했다 — 백업 명령 렌더링/실행은 JDBC와 무관한 관심사다.
 *
 * 보안 원칙 (전 기종 공통):
 * - 템플릿을 먼저 토큰으로 나눈 뒤 토큰 안에서만 치환한다 (값에 공백을 넣어 인자를 주입하는 것 방지)
 * - 치환 값은 허용 문자만 통과, "-" 시작 값(플래그 주입) 거부
 * - 비밀번호는 argv에 절대 싣지 않는다 — 환경변수(MYSQL_PWD/PGPASSWORD) 또는 stdin으로만
 */
public final class BackupCommands {

    private BackupCommands() {
    }

    public static List<String> render(String template, DatabaseInstance instance) {
        if (template.contains("{password}")) {
            throw new OperatorException(
                    "백업 명령에 {password}를 쓸 수 없습니다 — 비밀번호는 환경변수/stdin으로 전달됩니다", null);
        }
        return Arrays.stream(template.split(" "))
                .filter(t -> !t.isBlank())
                .map(t -> t
                        .replace("{host}", safeValue(instance.getHost()))
                        .replace("{port}", String.valueOf(instance.getPort()))
                        .replace("{user}", safeValue(instance.getUsername()))
                        .replace("{db}", safeValue(instance.getDbName())))
                .toList();
    }

    /** 산출물 암호화(선택) — 기동 시 BackupArtifactCipher가 1회 등록한다(정적 관문 유지, 주석 참고). */
    private static volatile BackupArtifactCipher cipher;

    static void artifactCipher(BackupArtifactCipher c) {
        cipher = c;
    }

    /**
     * CLI를 실행해 stdout을 파일로 받는다. stdinContent가 있으면 표준입력으로 흘려보낸다(mongodump --config /dev/stdin용).
     * 산출물 암호화 키가 설정돼 있으면 stdout을 AES-256-GCM으로 감싸 쓴다 — 파일명·체인 규약은 불변.
     */
    public static BackupResult run(List<String> command, Map<String, String> env, Path outFile, String stdinContent) {
        try {
            Files.createDirectories(outFile.getParent());
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().putAll(env);
            boolean encrypt = cipher != null && cipher.enabled();
            if (encrypt) {
                pb.redirectOutput(Redirect.PIPE);
            } else {
                pb.redirectOutput(outFile.toFile());
            }
            pb.redirectErrorStream(false);
            Process process = pb.start();
            if (stdinContent != null) {
                try (var stdin = process.getOutputStream()) {
                    stdin.write(stdinContent.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            if (encrypt) {
                // stdout을 현재 스레드에서 암호화 스트림으로 복사 — stderr는 종전과 같이 소량 가정(종료 후 수거)
                try (var out = cipher.wrap(Files.newOutputStream(outFile))) {
                    process.getInputStream().transferTo(out);
                }
            }
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                Files.deleteIfExists(outFile); // 실패 잔재가 체인 보충의 "이미 수집됨" 판정을 오염시키지 않게
                throw new OperatorException("백업 명령 실패(exit=" + exit + "): " + stderr.trim(), null);
            }
            return new BackupResult(outFile.toString(), Files.size(outFile));
        } catch (OperatorException e) {
            throw e;
        } catch (Exception e) {
            throw new OperatorException("백업 실행 실패: " + e.getMessage(), e);
        }
    }

    /**
     * mongodump --config /dev/stdin에 넣을 YAML 한 줄을 만든다.
     * 값을 그대로 이어붙이면 개행으로 다른 설정 키(uri: 등)를 주입할 수 있으므로,
     * 제어 문자를 거부하고 YAML 작은따옴표 스칼라로 감싼다(' -> '' 이스케이프).
     */
    public static String yamlEntry(String key, String value) {
        if (value == null || value.chars().anyMatch(Character::isISOControl)) {
            throw new OperatorException("백업 설정 값에 제어 문자를 쓸 수 없습니다: " + key, null);
        }
        return key + ": '" + value.replace("'", "''") + "'\n";
    }

    public static String timestamp() {
        return java.time.LocalDateTime.now().toString().replace(":", "-");
    }

    /** 파일 경로에 들어가는 이름은 안전한 문자만 남긴다 (경로 탈출 방지) */
    public static String safeFileName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safeValue(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+") || value.startsWith("-")) {
            throw new OperatorException("백업 명령에 쓸 수 없는 값: " + value, null);
        }
        return value;
    }
}
